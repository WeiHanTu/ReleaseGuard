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
  if [[ -n "${operator_pid}" ]] && kill -0 "${operator_pid}" >/dev/null 2>&1; then
    kill -TERM "${operator_pid}" >/dev/null 2>&1 || true
    wait "${operator_pid}" >/dev/null 2>&1 || true
  fi
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
RELEASEGUARD_CLEANUP_DEADLINE=90s \
RELEASEGUARD_CLEANUP_REQUEUE_INTERVAL=2s \
  "${REPOSITORY_ROOT}/release-operator/build/install/release-operator/bin/release-operator" \
  >"${OPERATOR_LOG}" 2>&1 &
operator_pid=$!
sleep 2
if ! kill -0 "${operator_pid}" >/dev/null 2>&1; then
  echo "ReleaseGuard operator exited during startup" >&2
  exit 1
fi

${KUBECTL_BIN} apply -f "${REPOSITORY_ROOT}/config/examples/metricpolicy-http-errors.yaml" >/dev/null
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

log "Starting a candidate revision and pausing at five percent canary weight"
${KUBECTL_BIN} -n "${TEST_NAMESPACE}" patch rollout checkout --type=merge \
  -p '{"spec":{"template":{"metadata":{"labels":{"releaseguard.e2e/revision":"candidate"}}}}}' \
  >/dev/null
wait_for_value rollout/checkout '{.status.phase}' Paused 180
wait_for_value ingress/checkout-checkout-canary \
  '{.metadata.annotations.nginx\.ingress\.kubernetes\.io/canary-weight}' 5 60
wait_for_value service/checkout-stable \
  '{.spec.selector.rollouts-pod-template-hash}' "${stable_revision}" 60

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

cat >"${EVIDENCE_FILE}" <<EOF
kind_version=$(${KIND_BIN} version)
kubernetes_version=$(${KUBECTL_BIN} version -o json | tr -d '\n')
argo_rollouts_version=${ARGO_ROLLOUTS_VERSION}
argo_install_sha256=${ARGO_INSTALL_SHA}
stable_revision=${stable_revision}
canary_weight_before_outage=5
abort_request_held_finalizer_without_controller=true
argo_aborted_at=${aborted_at}
canary_weight_after_recovery=0
stable_selector_after_recovery=${stable_revision}
managed_children_deleted=true
EOF
