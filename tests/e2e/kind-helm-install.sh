#!/usr/bin/env bash

set -euo pipefail

readonly REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly KIND_BIN="${KIND_BIN:-${REPOSITORY_ROOT}/.local/bin/kind}"
readonly HELM_BIN="${HELM_BIN:-${REPOSITORY_ROOT}/.local/bin/helm}"
readonly KUBECTL_BIN="${KUBECTL_BIN:-kubectl}"
readonly OPERATOR_IMAGE="${RELEASEGUARD_OPERATOR_IMAGE:-releaseguard-operator:dev}"
readonly KIND_NODE_IMAGE="kindest/node:v1.35.0@sha256:452d707d4862f52530247495d180205e029056831160e22870e37e3f6c1ac31f"
readonly ARGO_ROLLOUTS_VERSION="v1.9.1"
readonly ARGO_INSTALL_SHA="78c82343803c2bbc13a36049e269a532dd67f25b7e2cb3603c99e31d8d8a40b5"
readonly CLUSTER_NAME="${RELEASEGUARD_HELM_CLUSTER:-releaseguard-helm-${RANDOM}-$$}"
readonly OUTPUT_DIRECTORY="${RELEASEGUARD_HELM_OUTPUT:-${REPOSITORY_ROOT}/build/e2e/${CLUSTER_NAME}}"
readonly KUBECONFIG_PATH="${OUTPUT_DIRECTORY}/kubeconfig"
readonly EVIDENCE_FILE="${OUTPUT_DIRECTORY}/evidence.txt"
readonly TEST_NAMESPACE="releaseguard-e2e"
readonly OPERATOR_NAMESPACE="releaseguard-system"
readonly LOCAL_HEALTH_PORT=$((20000 + RANDOM % 20000))

cluster_created=0
port_forward_pid=""

log() {
  printf '[releaseguard-helm-e2e] %s\n' "$*"
}

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

diagnostics() {
  if ((cluster_created == 0)); then
    return
  fi
  log "Collecting failure diagnostics"
  ${KUBECTL_BIN} get namespaces,crds 2>&1 || true
  ${KUBECTL_BIN} -n "${OPERATOR_NAMESPACE}" get deployment,pods -o wide 2>&1 || true
  ${KUBECTL_BIN} -n "${OPERATOR_NAMESPACE}" describe deployment/releaseguard 2>&1 || true
  ${KUBECTL_BIN} -n "${OPERATOR_NAMESPACE}" logs deployment/releaseguard --tail=200 2>&1 || true
  ${KUBECTL_BIN} -n "${TEST_NAMESPACE}" get releaseplans,rollouts,services,ingresses -o wide 2>&1 || true
  ${KUBECTL_BIN} -n "${TEST_NAMESPACE}" get events --sort-by=.lastTimestamp 2>&1 || true
}

cleanup() {
  local exit_code=$?
  trap - EXIT
  if ((exit_code != 0)); then
    diagnostics
  fi
  if [[ -n "${port_forward_pid}" ]] && kill -0 "${port_forward_pid}" >/dev/null 2>&1; then
    kill -TERM "${port_forward_pid}" >/dev/null 2>&1 || true
    wait "${port_forward_pid}" >/dev/null 2>&1 || true
  fi
  if ((cluster_created == 1)) && [[ "${RELEASEGUARD_HELM_KEEP_CLUSTER:-0}" != "1" ]]; then
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

[[ -x "${KIND_BIN}" ]] || { echo "Run ./scripts/install-kind.sh first" >&2; exit 1; }
[[ -x "${HELM_BIN}" ]] || { echo "Run ./scripts/install-helm.sh first" >&2; exit 1; }
command -v "${KUBECTL_BIN}" >/dev/null
command -v curl >/dev/null
docker image inspect "${OPERATOR_IMAGE}" >/dev/null
mkdir -p "${OUTPUT_DIRECTORY}"

if "${KIND_BIN}" get clusters | grep -Fxq "${CLUSTER_NAME}"; then
  echo "Refusing to overwrite existing Kind cluster ${CLUSTER_NAME}" >&2
  exit 1
fi

log "Creating a clean Kind cluster"
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

log "Loading the verified image and installing the Helm chart"
"${KIND_BIN}" load docker-image "${OPERATOR_IMAGE}" --name "${CLUSTER_NAME}"
"${HELM_BIN}" upgrade --install releaseguard "${REPOSITORY_ROOT}/charts/releaseguard" \
  --namespace "${OPERATOR_NAMESPACE}" \
  --create-namespace \
  --set image.repository="${OPERATOR_IMAGE%:*}" \
  --set image.tag="${OPERATOR_IMAGE##*:}" \
  --set image.pullPolicy=Never \
  --wait \
  --timeout 180s
${KUBECTL_BIN} -n "${OPERATOR_NAMESPACE}" rollout status deployment/releaseguard --timeout=60s

operator_service_account="system:serviceaccount:${OPERATOR_NAMESPACE}:releaseguard"
[[ "$(${KUBECTL_BIN} auth can-i --as "${operator_service_account}" list releaseplans --all-namespaces)" == "yes" ]]
[[ "$(${KUBECTL_BIN} auth can-i --as "${operator_service_account}" patch rollouts.argoproj.io --all-namespaces)" == "yes" ]]
[[ "$(${KUBECTL_BIN} auth can-i --as "${operator_service_account}" get secrets --all-namespaces)" == "no" ]]
[[ "$(${KUBECTL_BIN} auth can-i --as "${operator_service_account}" delete namespaces)" == "no" ]]

${KUBECTL_BIN} -n "${OPERATOR_NAMESPACE}" port-forward deployment/releaseguard \
  "${LOCAL_HEALTH_PORT}:8081" >"${OUTPUT_DIRECTORY}/port-forward.log" 2>&1 &
port_forward_pid=$!
for _ in {1..30}; do
  if curl --fail --silent "http://127.0.0.1:${LOCAL_HEALTH_PORT}/healthz" >/dev/null \
    && curl --fail --silent "http://127.0.0.1:${LOCAL_HEALTH_PORT}/readyz" >/dev/null; then
    break
  fi
  sleep 1
done
curl --fail --silent "http://127.0.0.1:${LOCAL_HEALTH_PORT}/healthz" | grep -Fxq ok
curl --fail --silent "http://127.0.0.1:${LOCAL_HEALTH_PORT}/readyz" | grep -Fxq ok

log "Reconciling a ReleasePlan through the in-cluster operator"
${KUBECTL_BIN} create namespace "${TEST_NAMESPACE}"
${KUBECTL_BIN} apply -f "${REPOSITORY_ROOT}/config/examples/metricpolicy-http-errors.yaml" >/dev/null
policy_generation="$(${KUBECTL_BIN} get metricpolicy http-error-ratio -o jsonpath='{.metadata.generation}')"
${KUBECTL_BIN} patch metricpolicy http-error-ratio --subresource=status --type=merge \
  -p "{\"status\":{\"accepted\":true,\"observedGeneration\":${policy_generation},\"reason\":\"HelmE2EApproved\"}}" \
  >/dev/null
${KUBECTL_BIN} apply -f "${REPOSITORY_ROOT}/tests/e2e/fixtures/releaseplan-delete.yaml" >/dev/null
${KUBECTL_BIN} -n "${TEST_NAMESPACE}" wait \
  --for=jsonpath='{.status.phase}'=Bootstrapping releaseplan/checkout --timeout=60s
${KUBECTL_BIN} -n "${TEST_NAMESPACE}" wait --for=create rollout/checkout --timeout=60s

operator_image_id="$(${KUBECTL_BIN} -n "${OPERATOR_NAMESPACE}" get pod \
  -l app.kubernetes.io/component=operator -o jsonpath='{.items[0].status.containerStatuses[0].imageID}')"
chart_version="$(${HELM_BIN} list -n "${OPERATOR_NAMESPACE}" -o json | \
  sed -n 's/.*"chart":"releaseguard-\([^"]*\)".*/\1/p')"

if ${KUBECTL_BIN} -n "${OPERATOR_NAMESPACE}" logs deployment/releaseguard | \
  grep -Eq 'Forbidden|permission denied|Exhausted retries|Uncaught error'; then
  echo "Operator log contains an RBAC or reconciliation failure" >&2
  exit 1
fi

cat >"${EVIDENCE_FILE}" <<EOF
kind_version=$(${KIND_BIN} version)
kubernetes_version=$(${KUBECTL_BIN} version -o json | tr -d '\n')
helm_version=$(${HELM_BIN} version --short)
chart_version=${chart_version}
argo_rollouts_version=${ARGO_ROLLOUTS_VERSION}
argo_install_sha256=${ARGO_INSTALL_SHA}
operator_image=${OPERATOR_IMAGE}
operator_image_id=${operator_image_id}
operator_ready=true
healthz=200
readyz=200
cluster_wide_releaseplan_list=yes
cluster_wide_secret_read=no
namespace_delete=no
releaseplan_phase=Bootstrapping
rollout_created=true
EOF
