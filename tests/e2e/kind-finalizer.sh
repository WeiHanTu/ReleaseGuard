#!/usr/bin/env bash

set -euo pipefail

readonly REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly KIND_BIN="${KIND_BIN:-${REPOSITORY_ROOT}/.local/bin/kind}"
readonly KUBECTL_BIN="${KUBECTL_BIN:-kubectl}"
readonly KIND_NODE_IMAGE="kindest/node:v1.35.0@sha256:452d707d4862f52530247495d180205e029056831160e22870e37e3f6c1ac31f"
readonly ARGO_ROLLOUTS_VERSION="v1.9.1"
readonly ARGO_INSTALL_SHA="78c82343803c2bbc13a36049e269a532dd67f25b7e2cb3603c99e31d8d8a40b5"
readonly TEST_NAMESPACE="releaseguard-e2e"
readonly CLUSTER_NAME="${RELEASEGUARD_E2E_CLUSTER:-releaseguard-e2e-${RANDOM}-$$}"
readonly OUTPUT_DIRECTORY="${RELEASEGUARD_E2E_OUTPUT:-${REPOSITORY_ROOT}/build/e2e/${CLUSTER_NAME}}"
readonly KUBECONFIG_PATH="${OUTPUT_DIRECTORY}/kubeconfig"
readonly OPERATOR_LOG="${OUTPUT_DIRECTORY}/releaseguard-operator.log"
readonly EVIDENCE_FILE="${OUTPUT_DIRECTORY}/evidence.txt"

cluster_created=0
operator_pid=""
rollout_watch_pid=""
ingress_watch_pid=""

log() {
  printf '[releaseguard-e2e] %s\n' "$*"
}

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

wait_for_value() {
  local resource="$1"
  local jsonpath="$2"
  local expected="$3"
  local timeout_seconds="$4"
  local deadline=$((SECONDS + timeout_seconds))
  local actual=""
  while ((SECONDS < deadline)); do
    actual="$(${KUBECTL_BIN} -n "${TEST_NAMESPACE}" get "${resource}" -o "jsonpath=${jsonpath}" 2>/dev/null || true)"
    if [[ "${actual}" == "${expected}" ]]; then
      return 0
    fi
    sleep 1
  done
  echo "Timed out waiting for ${resource} ${jsonpath}=${expected}; last value: ${actual}" >&2
  return 1
}

wait_for_nonempty() {
  local resource="$1"
  local jsonpath="$2"
  local timeout_seconds="$3"
  local deadline=$((SECONDS + timeout_seconds))
  local actual=""
  while ((SECONDS < deadline)); do
    actual="$(${KUBECTL_BIN} -n "${TEST_NAMESPACE}" get "${resource}" -o "jsonpath=${jsonpath}" 2>/dev/null || true)"
    if [[ -n "${actual}" ]]; then
      printf '%s' "${actual}"
      return 0
    fi
    sleep 1
  done
  echo "Timed out waiting for nonempty ${resource} ${jsonpath}" >&2
  return 1
}

wait_for_replicaset_by_hash() {
  local revision="$1"
  local timeout_seconds="$2"
  local deadline=$((SECONDS + timeout_seconds))
  local actual=""
  while ((SECONDS < deadline)); do
    actual="$(${KUBECTL_BIN} -n "${TEST_NAMESPACE}" get replicasets \
      -l "rollouts-pod-template-hash=${revision}" \
      -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
    if [[ -n "${actual}" ]]; then
      printf '%s' "${actual}"
      return 0
    fi
    sleep 1
  done
  echo "Timed out waiting for ReplicaSet with revision ${revision}" >&2
  return 1
}

wait_for_absent() {
  local resource="$1"
  local timeout_seconds="$2"
  local deadline=$((SECONDS + timeout_seconds))
  while ((SECONDS < deadline)); do
    if ! ${KUBECTL_BIN} -n "${TEST_NAMESPACE}" get "${resource}" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "Timed out waiting for ${resource} to disappear" >&2
  return 1
}

diagnostics() {
  if ((cluster_created == 0)); then
    return
  fi
  log "Collecting failure diagnostics"
  ${KUBECTL_BIN} get namespaces,crds 2>&1 || true
  ${KUBECTL_BIN} -n argo-rollouts get deployment,pods 2>&1 || true
  ${KUBECTL_BIN} -n argo-rollouts logs deployment/argo-rollouts --tail=200 2>&1 || true
  ${KUBECTL_BIN} -n "${TEST_NAMESPACE}" get releaseplans,rollouts,services,ingresses,endpoints,pods,replicasets -o wide 2>&1 || true
  ${KUBECTL_BIN} -n "${TEST_NAMESPACE}" get events --sort-by=.lastTimestamp 2>&1 || true
  if [[ -f "${OPERATOR_LOG}" ]]; then
    tail -200 "${OPERATOR_LOG}" 2>&1 || true
  fi
}

stop_watchers() {
  local watcher_pid
  for watcher_pid in "${rollout_watch_pid}" "${ingress_watch_pid}"; do
    if [[ -n "${watcher_pid}" ]] && kill -0 "${watcher_pid}" >/dev/null 2>&1; then
      kill -TERM "${watcher_pid}" >/dev/null 2>&1 || true
      wait "${watcher_pid}" >/dev/null 2>&1 || true
    fi
  done
  rollout_watch_pid=""
  ingress_watch_pid=""
}

stop_operator() {
  if [[ -n "${operator_pid}" ]] && kill -0 "${operator_pid}" >/dev/null 2>&1; then
    kill -TERM "${operator_pid}" >/dev/null 2>&1 || true
    wait "${operator_pid}" >/dev/null 2>&1 || true
  fi
  operator_pid=""
}

start_operator() {
  RELEASEGUARD_CLEANUP_DEADLINE=90s \
  RELEASEGUARD_CLEANUP_REQUEUE_INTERVAL=2s \
    "${REPOSITORY_ROOT}/release-operator/build/install/release-operator/bin/release-operator" \
    >>"${OPERATOR_LOG}" 2>&1 &
  operator_pid=$!
  sleep 2
  if ! kill -0 "${operator_pid}" >/dev/null 2>&1; then
    echo "ReleaseGuard operator exited during startup" >&2
    return 1
  fi
}

assert_detached() {
  local resource="$1"
  local forbidden_owner="$2"
  local owners
  local managed_by
  owners="$(${KUBECTL_BIN} -n "${TEST_NAMESPACE}" get "${resource}" \
    -o jsonpath='{range .metadata.ownerReferences[*]}{.kind}{"/"}{.name}{" "}{end}')"
  managed_by="$(${KUBECTL_BIN} -n "${TEST_NAMESPACE}" get "${resource}" \
    -o jsonpath='{.metadata.labels.app\.kubernetes\.io/managed-by}')"
  if [[ "${owners}" == *"${forbidden_owner}"* ]]; then
    echo "${resource} still has forbidden owner ${forbidden_owner}: ${owners}" >&2
    return 1
  fi
  if [[ "${managed_by}" == "releaseguard" ]]; then
    echo "${resource} still has the ReleaseGuard managed-by label" >&2
    return 1
  fi
}

start_candidate_release() {
  local marker="$1"
  # This cluster deliberately runs no metric-gate service, so the bootstrap AnalysisRun cannot
  # reach a verdict. Simulate the admitted bootstrap here to exercise a real second ReleasePlan
  # generation rather than mutate a child resource.
  ${KUBECTL_BIN} -n "${TEST_NAMESPACE}" patch releaseplan checkout \
    --subresource=status --type=merge -p '{"status":{"phase":"Succeeded"}}' >/dev/null
  wait_for_value releaseplan/checkout '{.status.phase}' Succeeded 30
  ${KUBECTL_BIN} -n "${TEST_NAMESPACE}" patch releaseplan checkout --type=merge \
    -p "{\"spec\":{\"workload\":{\"podTemplate\":{\"metadata\":{\"labels\":{\"releaseguard.e2e/revision\":\"${marker}\"}}}}}}" \
    >/dev/null
  local generation
  generation="$(${KUBECTL_BIN} -n "${TEST_NAMESPACE}" get releaseplan checkout \
    -o jsonpath='{.metadata.generation}')"
  wait_for_value releaseplan/checkout '{.status.observedGeneration}' "${generation}" 30
}

cleanup() {
  local exit_code=$?
  trap - EXIT
  if ((exit_code == 0)) && [[ ! -s "${EVIDENCE_FILE}" ]]; then
    echo "E2E process exited without producing evidence" >&2
    exit_code=1
  fi
  if ((exit_code != 0)); then
    diagnostics
  fi
  stop_watchers
  stop_operator
  if ((cluster_created == 1)) && [[ "${RELEASEGUARD_E2E_KEEP_CLUSTER:-0}" != "1" ]]; then
    "${KIND_BIN}" delete cluster --name "${CLUSTER_NAME}" >/dev/null 2>&1 || true
    rm -f "${KUBECONFIG_PATH}"
  fi
  if ((exit_code == 0)); then
    log "PASS; evidence written to ${EVIDENCE_FILE}"
  else
    log "FAIL; diagnostics retained in ${OUTPUT_DIRECTORY}" >&2
  fi
  exit "${exit_code}"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if [[ ! -x "${KIND_BIN}" ]]; then
  echo "kind is missing at ${KIND_BIN}; run ./scripts/install-kind.sh" >&2
  exit 1
fi
command -v "${KUBECTL_BIN}" >/dev/null
command -v curl >/dev/null
mkdir -p "${OUTPUT_DIRECTORY}"

if "${KIND_BIN}" get clusters | grep -Fxq "${CLUSTER_NAME}"; then
  echo "Refusing to overwrite existing Kind cluster ${CLUSTER_NAME}" >&2
  exit 1
fi

log "Creating ${CLUSTER_NAME} with pinned Kubernetes node image"
"${KIND_BIN}" create cluster \
  --name "${CLUSTER_NAME}" \
  --image "${KIND_NODE_IMAGE}" \
  --kubeconfig "${KUBECONFIG_PATH}" \
  --wait 180s
cluster_created=1
export KUBECONFIG="${KUBECONFIG_PATH}"

argo_manifest="${OUTPUT_DIRECTORY}/argo-rollouts-${ARGO_ROLLOUTS_VERSION}.yaml"
curl --fail --location --silent --show-error \
  "https://github.com/argoproj/argo-rollouts/releases/download/${ARGO_ROLLOUTS_VERSION}/install.yaml" \
  --output "${argo_manifest}"
actual_argo_sha="$(sha256 "${argo_manifest}")"
if [[ "${actual_argo_sha}" != "${ARGO_INSTALL_SHA}" ]]; then
  echo "Argo Rollouts manifest checksum mismatch: ${actual_argo_sha}" >&2
  exit 1
fi

log "Installing Argo Rollouts ${ARGO_ROLLOUTS_VERSION}"
${KUBECTL_BIN} create namespace argo-rollouts
${KUBECTL_BIN} -n argo-rollouts apply -f "${argo_manifest}" >/dev/null
${KUBECTL_BIN} -n argo-rollouts rollout status deployment/argo-rollouts --timeout=180s

log "Installing ReleaseGuard CRDs and starting the operator locally"
${KUBECTL_BIN} apply -f "${REPOSITORY_ROOT}/config/crd" >/dev/null
${KUBECTL_BIN} create namespace "${TEST_NAMESPACE}"
"${REPOSITORY_ROOT}/gradlew" -p "${REPOSITORY_ROOT}" :release-operator:installDist --no-daemon
start_operator

# Argo resolves the generated AnalysisTemplate token argument from this Secret before it starts a
# measurement. A release namespace without it fails the run closed instead of promoting.
${KUBECTL_BIN} -n "${TEST_NAMESPACE}" create secret generic releaseguard-metric-gate-auth \
  --from-literal="token=$(head -c 32 /dev/urandom | base64 | tr -d '\n')" >/dev/null
${KUBECTL_BIN} apply -f "${REPOSITORY_ROOT}/tests/e2e/fixtures/metricpolicy-gate-hold.yaml" >/dev/null
policy_generation="$(${KUBECTL_BIN} get metricpolicy http-error-ratio -o jsonpath='{.metadata.generation}')"
${KUBECTL_BIN} patch metricpolicy http-error-ratio --subresource=status --type=merge \
  -p "{\"status\":{\"accepted\":true,\"observedGeneration\":${policy_generation},\"reason\":\"E2EApproved\"}}" \
  >/dev/null
${KUBECTL_BIN} apply -f "${REPOSITORY_ROOT}/tests/e2e/fixtures/releaseplan-delete.yaml" >/dev/null

log "Waiting for a real Argo Rollout to establish the initial stable revision"
wait_for_value releaseplan/checkout '{.status.phase}' Bootstrapping 60
wait_for_value rollout/checkout '{.status.phase}' Healthy 180
stable_revision="$(wait_for_nonempty rollout/checkout '{.status.stableRS}' 30)"
wait_for_nonempty endpoints/checkout-stable '{.subsets[0].addresses[0].ip}' 60 >/dev/null
wait_for_value releaseplan/checkout \
  '{.status.stableRevision}' "checkout-${stable_revision}" 60

log "Verifying the bootstrap release is gated instead of admitted on readiness alone"
bootstrap_generation="$(${KUBECTL_BIN} -n "${TEST_NAMESPACE}" get releaseplan checkout \
  -o jsonpath='{.metadata.generation}')"
bootstrap_run="checkout-bootstrap-g${bootstrap_generation}"
${KUBECTL_BIN} -n "${TEST_NAMESPACE}" wait --for=create "analysisrun/${bootstrap_run}" --timeout=90s
bootstrap_mode="$(${KUBECTL_BIN} -n "${TEST_NAMESPACE}" get "analysisrun/${bootstrap_run}" \
  -o jsonpath='{.spec.args[?(@.name=="mode")].value}')"
bootstrap_baseline="$(${KUBECTL_BIN} -n "${TEST_NAMESPACE}" get "analysisrun/${bootstrap_run}" \
  -o jsonpath='{.spec.args[?(@.name=="stable-revision")].value}')"
[[ "${bootstrap_mode}" == "Bootstrap" ]]
[[ -z "${bootstrap_baseline}" ]]
bootstrap_phase_before_verdict="$(${KUBECTL_BIN} -n "${TEST_NAMESPACE}" get releaseplan checkout \
  -o jsonpath='{.status.phase}')"
if [[ "${bootstrap_phase_before_verdict}" != "Bootstrapping" ]]; then
  echo "Bootstrap release left Bootstrapping without a gate verdict" >&2
  exit 1
fi

log "Starting a candidate ReleasePlan generation and holding the first gated canary step"
start_candidate_release candidate
wait_for_value ingress/checkout-checkout-canary \
  '{.metadata.annotations.nginx\.ingress\.kubernetes\.io/canary-weight}' 5 180
delete_analysis_run="$(wait_for_nonempty rollout/checkout \
  '{.status.canary.currentStepAnalysisRunStatus.name}' 120)"
wait_for_value releaseplan/checkout '{.status.phase}' Progressing 60
wait_for_value releaseplan/checkout '{.status.currentStep}' 0 60
wait_for_value service/checkout-stable \
  '{.spec.selector.rollouts-pod-template-hash}' "${stable_revision}" 60
candidate_revision="$(wait_for_nonempty rollout/checkout '{.status.currentPodHash}' 30)"
wait_for_value releaseplan/checkout \
  '{.status.canaryRevision}' "checkout-${candidate_revision}" 60
delete_releaseplan_phase="$(${KUBECTL_BIN} -n "${TEST_NAMESPACE}" get releaseplan checkout \
  -o jsonpath='{.status.phase}')"
delete_releaseplan_step="$(${KUBECTL_BIN} -n "${TEST_NAMESPACE}" get releaseplan checkout \
  -o jsonpath='{.status.currentStep}')"

log "Stopping Argo Rollouts to verify the finalizer does not trust its own abort request"
${KUBECTL_BIN} -n argo-rollouts scale deployment/argo-rollouts --replicas=0 >/dev/null
${KUBECTL_BIN} -n argo-rollouts wait --for=delete pod \
  -l app.kubernetes.io/name=argo-rollouts --timeout=60s
${KUBECTL_BIN} -n "${TEST_NAMESPACE}" delete releaseplan checkout --wait=false >/dev/null
wait_for_value rollout/checkout '{.status.abort}' true 30
sleep 3

if ! ${KUBECTL_BIN} -n "${TEST_NAMESPACE}" get releaseplan checkout >/dev/null 2>&1; then
  echo "ReleasePlan finalized before Argo acknowledged the abort" >&2
  exit 1
fi
if [[ -n "$(${KUBECTL_BIN} -n "${TEST_NAMESPACE}" get rollout checkout -o jsonpath='{.status.abortedAt}')" ]]; then
  echo "Argo reported abortedAt while its controller was stopped" >&2
  exit 1
fi
finalizers="$(${KUBECTL_BIN} -n "${TEST_NAMESPACE}" get releaseplan checkout -o jsonpath='{.metadata.finalizers[*]}')"
if [[ "${finalizers}" != *"delivery.weihantu.dev/releaseguard-cleanup"* ]]; then
  echo "ReleaseGuard cleanup finalizer was not retained during controller outage" >&2
  exit 1
fi

rollout_watch_log="${OUTPUT_DIRECTORY}/rollout-watch.log"
ingress_watch_log="${OUTPUT_DIRECTORY}/canary-ingress-watch.log"
${KUBECTL_BIN} -n "${TEST_NAMESPACE}" get rollout checkout --watch \
  -o custom-columns='ABORT:.status.abort,ABORTED_AT:.status.abortedAt,PHASE:.status.phase' \
  --no-headers >"${rollout_watch_log}" 2>&1 &
rollout_watch_pid=$!
${KUBECTL_BIN} -n "${TEST_NAMESPACE}" get ingress checkout-checkout-canary --watch \
  -o custom-columns='WEIGHT:.metadata.annotations.nginx\.ingress\.kubernetes\.io/canary-weight' \
  --no-headers >"${ingress_watch_log}" 2>&1 &
ingress_watch_pid=$!
sleep 1

log "Restarting Argo Rollouts and verifying safe recovery and deletion"
${KUBECTL_BIN} -n argo-rollouts scale deployment/argo-rollouts --replicas=1 >/dev/null
${KUBECTL_BIN} -n argo-rollouts rollout status deployment/argo-rollouts --timeout=120s
wait_for_absent releaseplan/checkout 60
wait_for_absent rollout/checkout 30
wait_for_absent service/checkout-stable 30
wait_for_absent service/checkout-canary 30
wait_for_absent ingress/checkout 30
wait_for_absent ingress/checkout-checkout-canary 30
stop_watchers

if ! grep -Eq '^true[[:space:]]+20[0-9]{2}-' "${rollout_watch_log}"; then
  echo "Rollout watch did not capture Argo's abortedAt acknowledgment" >&2
  exit 1
fi
if ! grep -Eq '^0$' "${ingress_watch_log}"; then
  echo "Ingress watch did not capture canary weight restoration to zero" >&2
  exit 1
fi
aborted_at="$(awk '$1 == "true" && $2 ~ /^20[0-9][0-9]-/ {print $2; exit}' "${rollout_watch_log}")"

delete_stable_revision="${stable_revision}"
delete_aborted_at="${aborted_at}"

log "Creating an OrphanStable release and advancing it to five percent canary weight"
${KUBECTL_BIN} apply \
  -f "${REPOSITORY_ROOT}/tests/e2e/fixtures/releaseplan-orphan-stable.yaml" >/dev/null
wait_for_value releaseplan/checkout '{.status.phase}' Bootstrapping 60
wait_for_value rollout/checkout '{.status.phase}' Healthy 180
orphan_stable_revision="$(wait_for_nonempty rollout/checkout '{.status.stableRS}' 30)"
wait_for_nonempty endpoints/checkout-stable '{.subsets[0].addresses[0].ip}' 60 >/dev/null
wait_for_value releaseplan/checkout \
  '{.status.stableRevision}' "checkout-${orphan_stable_revision}" 60
orphan_stable_replicaset="$(wait_for_replicaset_by_hash "${orphan_stable_revision}" 30)"
orphan_plan_uid="$(${KUBECTL_BIN} -n "${TEST_NAMESPACE}" get releaseplan checkout \
  -o jsonpath='{.metadata.uid}')"

start_candidate_release orphan-candidate
wait_for_value ingress/checkout-checkout-canary \
  '{.metadata.annotations.nginx\.ingress\.kubernetes\.io/canary-weight}' 5 180
orphan_analysis_run="$(wait_for_nonempty rollout/checkout \
  '{.status.canary.currentStepAnalysisRunStatus.name}' 120)"
wait_for_value releaseplan/checkout '{.status.phase}' Progressing 60
wait_for_value releaseplan/checkout '{.status.currentStep}' 0 60
orphan_candidate_revision="$(wait_for_nonempty rollout/checkout '{.status.currentPodHash}' 30)"
wait_for_value releaseplan/checkout \
  '{.status.canaryRevision}' "checkout-${orphan_candidate_revision}" 60
orphan_candidate_replicaset="checkout-${orphan_candidate_revision}"

log "Deleting with OrphanStable and verifying a controller-independent stable workload"
${KUBECTL_BIN} -n "${TEST_NAMESPACE}" delete releaseplan checkout --wait=false >/dev/null
wait_for_absent releaseplan/checkout 90
wait_for_absent rollout/checkout 30
wait_for_absent "replicaset/${orphan_candidate_replicaset}" 30
wait_for_absent service/checkout-canary 30
wait_for_absent ingress/checkout-checkout-canary 30
wait_for_value "replicaset/${orphan_stable_replicaset}" '{.status.readyReplicas}' 2 60
wait_for_nonempty endpoints/checkout-stable '{.subsets[0].addresses[0].ip}' 30 >/dev/null
assert_detached "replicaset/${orphan_stable_replicaset}" "Rollout/checkout"
assert_detached service/checkout-stable "ReleasePlan/checkout"
assert_detached ingress/checkout "ReleasePlan/checkout"

retained_replicasets="$(${KUBECTL_BIN} -n "${TEST_NAMESPACE}" get replicasets \
  -l "delivery.weihantu.dev/plan-uid=${orphan_plan_uid}" \
  -o name 2>/dev/null || true)"
if [[ "${retained_replicasets}" != "replicaset.apps/${orphan_stable_replicaset}" ]]; then
  echo "Expected only ${orphan_stable_replicaset}; found: ${retained_replicasets}" >&2
  exit 1
fi

log "Restarting both controllers and proving deleted canary resources are not recreated"
${KUBECTL_BIN} -n argo-rollouts rollout restart deployment/argo-rollouts >/dev/null
${KUBECTL_BIN} -n argo-rollouts rollout status deployment/argo-rollouts --timeout=120s
stop_operator
start_operator
sleep 10
if ${KUBECTL_BIN} -n "${TEST_NAMESPACE}" get rollout/checkout >/dev/null 2>&1 \
  || ${KUBECTL_BIN} -n "${TEST_NAMESPACE}" get service/checkout-canary >/dev/null 2>&1 \
  || ${KUBECTL_BIN} -n "${TEST_NAMESPACE}" get ingress/checkout-checkout-canary >/dev/null 2>&1 \
  || ${KUBECTL_BIN} -n "${TEST_NAMESPACE}" get "replicaset/${orphan_candidate_replicaset}" >/dev/null 2>&1; then
  echo "A controller recreated an OrphanStable canary resource" >&2
  exit 1
fi
wait_for_value "replicaset/${orphan_stable_replicaset}" '{.status.readyReplicas}' 2 30
wait_for_nonempty endpoints/checkout-stable '{.subsets[0].addresses[0].ip}' 30 >/dev/null
assert_detached "replicaset/${orphan_stable_replicaset}" "Rollout/checkout"
assert_detached service/checkout-stable "ReleasePlan/checkout"
assert_detached ingress/checkout "ReleasePlan/checkout"

ownership_conflicts="$(grep -c 'FieldManagerConflict' "${OPERATOR_LOG}" || true)"
if ((ownership_conflicts > 5)); then
  echo "ReleaseGuard operator exceeded the bounded ownership-conflict allowance: ${ownership_conflicts}" >&2
  exit 1
fi
if grep -Eq 'Exhausted retries|Uncaught error' "${OPERATOR_LOG}"; then
  echo "ReleaseGuard operator log contains an exhausted retry or uncaught error" >&2
  exit 1
fi

cat >"${EVIDENCE_FILE}" <<EOF
kind_version=$(${KIND_BIN} version)
kubernetes_version=$(${KUBECTL_BIN} version -o json | tr -d '\n')
argo_rollouts_version=${ARGO_ROLLOUTS_VERSION}
argo_install_sha256=${ARGO_INSTALL_SHA}
delete_stable_revision=${delete_stable_revision}
delete_candidate_revision=${candidate_revision}
releaseplan_phase_at_canary=${delete_releaseplan_phase}
releaseplan_current_step_at_canary=${delete_releaseplan_step}
bootstrap_analysis_run=${bootstrap_run}
bootstrap_analysis_mode=${bootstrap_mode}
bootstrap_baseline_supplied=false
releaseplan_phase_before_bootstrap_verdict=${bootstrap_phase_before_verdict}
step_analysis_run=${delete_analysis_run}
orphan_step_analysis_run=${orphan_analysis_run}
child_event_sources=Rollout,Service,Ingress
service_selector_handoff_conflicts=${ownership_conflicts}
canary_weight_before_outage=5
abort_request_held_finalizer_without_controller=true
delete_argo_aborted_at=${delete_aborted_at}
canary_weight_after_recovery=0
stable_selector_after_recovery=${delete_stable_revision}
managed_children_deleted=true
orphan_stable_revision=${orphan_stable_revision}
orphan_stable_replicaset=${orphan_stable_replicaset}
orphan_candidate_replicaset_deleted=true
orphan_rollout_deleted=true
orphan_stable_resources_detached=true
orphan_ready_replicas_after_restart=2
orphan_canary_not_recreated_after_restart=true
EOF
