#!/usr/bin/env bash

set -euo pipefail

readonly REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly KIND_BIN="${KIND_BIN:-${REPOSITORY_ROOT}/.local/bin/kind}"
readonly HELM_BIN="${HELM_BIN:-${REPOSITORY_ROOT}/.local/bin/helm}"
readonly KUBECTL_BIN="${KUBECTL_BIN:-kubectl}"
readonly OPERATOR_IMAGE="${RELEASEGUARD_OPERATOR_IMAGE:-releaseguard-operator:dev}"
readonly GATE_IMAGE="${RELEASEGUARD_GATE_IMAGE:-releaseguard-metric-gate:dev}"
readonly CLUSTER_NAME="${RELEASEGUARD_GITOPS_CLUSTER:-releaseguard-gitops-${RANDOM}-$$}"
readonly OUTPUT_DIRECTORY="${RELEASEGUARD_GITOPS_OUTPUT:-${REPOSITORY_ROOT}/build/e2e/${CLUSTER_NAME}}"
readonly BOOTSTRAP_OUTPUT="${OUTPUT_DIRECTORY}/bootstrap"
readonly KUBECONFIG_PATH="${BOOTSTRAP_OUTPUT}/kubeconfig"
readonly EVIDENCE_FILE="${OUTPUT_DIRECTORY}/evidence.txt"
readonly APPLICATION_NAME="releaseguard-local"
readonly APPLICATION_NAMESPACE="argocd"
readonly RELEASE_NAMESPACE="releaseguard-gitops"
readonly RELEASE_PLAN="gitops-checkout"
readonly GIT_SERVER_CONTAINER="${CLUSTER_NAME}-git"
readonly GIT_SERVER_IMAGE="releaseguard-git-server:test"

cluster_expected=0
git_server_started=0

log() {
  printf '[releaseguard-gitops-e2e] %s\n' "$*"
}

application_revision() {
  "${KUBECTL_BIN}" --namespace "${APPLICATION_NAMESPACE}" get \
    application "${APPLICATION_NAME}" \
    -o jsonpath='{.status.sync.revision}' 2>/dev/null || true
}

application_sync_status() {
  "${KUBECTL_BIN}" --namespace "${APPLICATION_NAMESPACE}" get \
    application "${APPLICATION_NAME}" \
    -o jsonpath='{.status.sync.status}' 2>/dev/null || true
}

plan_configuration_revision() {
  "${KUBECTL_BIN}" --namespace "${RELEASE_NAMESPACE}" get \
    releaseplan "${RELEASE_PLAN}" \
    -o jsonpath='{.metadata.labels.delivery\.weihantu\.dev/configuration-revision}' \
    2>/dev/null || true
}

wait_for_revision_and_configuration() {
  local expected_revision="$1"
  local expected_configuration="$2"
  local timeout_seconds="$3"
  local deadline=$((SECONDS + timeout_seconds))

  while ((SECONDS < deadline)); do
    if [[ "$(application_revision)" == "${expected_revision}" ]] \
      && [[ "$(application_sync_status)" == "Synced" ]] \
      && [[ "$(plan_configuration_revision)" == "${expected_configuration}" ]]; then
      return 0
    fi
    sleep 2
  done

  echo "Timed out waiting for revision ${expected_revision} and configuration ${expected_configuration}" >&2
  return 1
}

wait_for_configuration() {
  local expected_configuration="$1"
  local timeout_seconds="$2"
  local deadline=$((SECONDS + timeout_seconds))

  while ((SECONDS < deadline)); do
    if [[ "$(plan_configuration_revision)" == "${expected_configuration}" ]]; then
      return 0
    fi
    sleep 1
  done

  echo "Timed out waiting for ReleasePlan configuration ${expected_configuration}" >&2
  return 1
}

diagnostics() {
  if [[ ! -f "${KUBECONFIG_PATH}" ]]; then
    return
  fi
  log "Collecting failure diagnostics"
  "${KUBECTL_BIN}" --namespace "${APPLICATION_NAMESPACE}" get application "${APPLICATION_NAME}" -o yaml 2>&1 || true
  "${KUBECTL_BIN}" --namespace "${APPLICATION_NAMESPACE}" get pods -o wide 2>&1 || true
  "${KUBECTL_BIN}" --namespace "${APPLICATION_NAMESPACE}" logs deployment/argocd-repo-server --tail=200 2>&1 || true
  "${KUBECTL_BIN}" --namespace "${RELEASE_NAMESPACE}" get releaseplans,rollouts -o yaml 2>&1 || true
  docker logs "${GIT_SERVER_CONTAINER}" 2>&1 || true
}

cleanup() {
  local exit_code=$?
  trap - EXIT
  if ((exit_code != 0)); then
    diagnostics
  fi
  if ((git_server_started == 1)); then
    docker stop --time 5 "${GIT_SERVER_CONTAINER}" >/dev/null 2>&1 || true
  fi
  if ((cluster_expected == 1)) && [[ "${RELEASEGUARD_GITOPS_KEEP_CLUSTER:-0}" != "1" ]]; then
    "${KIND_BIN}" delete cluster --name "${CLUSTER_NAME}" >/dev/null 2>&1 || true
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
command -v docker >/dev/null
command -v git >/dev/null
docker image inspect "${OPERATOR_IMAGE}" >/dev/null
docker image inspect "${GATE_IMAGE}" >/dev/null
mkdir -p "${OUTPUT_DIRECTORY}"

if "${KIND_BIN}" get clusters | grep -Fxq "${CLUSTER_NAME}"; then
  echo "Refusing to overwrite existing Kind cluster ${CLUSTER_NAME}" >&2
  exit 1
fi
if docker container inspect "${GIT_SERVER_CONTAINER}" >/dev/null 2>&1; then
  echo "Refusing to overwrite existing container ${GIT_SERVER_CONTAINER}" >&2
  exit 1
fi

cluster_expected=1
log "Bootstrapping the verified chart and Argo Rollouts on a clean cluster"
RELEASEGUARD_HELM_CLUSTER="${CLUSTER_NAME}" \
RELEASEGUARD_HELM_OUTPUT="${BOOTSTRAP_OUTPUT}" \
RELEASEGUARD_HELM_KEEP_CLUSTER=1 \
RELEASEGUARD_OPERATOR_IMAGE="${OPERATOR_IMAGE}" \
RELEASEGUARD_GATE_IMAGE="${GATE_IMAGE}" \
  "${REPOSITORY_ROOT}/tests/e2e/kind-helm-install.sh"
export KUBECONFIG="${KUBECONFIG_PATH}"

git_source="${OUTPUT_DIRECTORY}/git-source"
git_remote_parent="${OUTPUT_DIRECTORY}/git-remote"
git_remote="${git_remote_parent}/releaseguard-environment.git"
mkdir -p "${git_source}/environments" "${git_remote_parent}"
cp -R "${REPOSITORY_ROOT}/environments/local" "${git_source}/environments/local"
git -C "${git_source}" init --initial-branch=main >/dev/null
git -C "${git_source}" add environments/local
git -C "${git_source}" \
  -c user.name=ReleaseGuard -c user.email=releaseguard@example.invalid \
  commit -m "Define the local ReleaseGuard environment" >/dev/null
git init --bare --initial-branch=main "${git_remote}" >/dev/null
git -C "${git_source}" remote add origin "${git_remote}"
git -C "${git_source}" push --set-upstream origin main >/dev/null
initial_revision="$(git -C "${git_source}" rev-parse HEAD)"

log "Serving the disposable Git remote inside the Kind Docker network"
docker build \
  --file "${REPOSITORY_ROOT}/tests/e2e/git-server.Dockerfile" \
  --tag "${GIT_SERVER_IMAGE}" \
  "${REPOSITORY_ROOT}/tests/e2e" >/dev/null
[[ "$(docker image inspect "${GIT_SERVER_IMAGE}" --format '{{.Config.User}}')" == "65534:65534" ]]
docker run --detach --rm \
  --name "${GIT_SERVER_CONTAINER}" \
  --network kind \
  --volume "${git_remote_parent}:/srv/git:ro" \
  "${GIT_SERVER_IMAGE}" \
  >/dev/null
git_server_started=1
git_server_ip="$(docker container inspect "${GIT_SERVER_CONTAINER}" \
  --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}')"
[[ -n "${git_server_ip}" ]]
git_repository_url="git://${git_server_ip}:9418/releaseguard-environment.git"

log "Installing checksum-pinned Argo CD"
"${REPOSITORY_ROOT}/scripts/install-argocd.sh"
"${KUBECTL_BIN}" --namespace "${APPLICATION_NAMESPACE}" patch configmap argocd-cm --type=merge \
  -p '{"data":{"timeout.reconciliation":"5s","timeout.reconciliation.jitter":"0s"}}' \
  >/dev/null
"${KUBECTL_BIN}" --namespace "${APPLICATION_NAMESPACE}" patch configmap argocd-cmd-params-cm --type=merge \
  -p '{"data":{"reposerver.repo.cache.expiration":"5s","reposerver.default.cache.expiration":"5s"}}' \
  >/dev/null
"${KUBECTL_BIN}" --namespace "${APPLICATION_NAMESPACE}" rollout restart \
  statefulset/argocd-application-controller >/dev/null
"${KUBECTL_BIN}" --namespace "${APPLICATION_NAMESPACE}" rollout restart \
  deployment/argocd-repo-server >/dev/null
"${KUBECTL_BIN}" --namespace "${APPLICATION_NAMESPACE}" rollout status \
  statefulset/argocd-application-controller --timeout=180s
"${KUBECTL_BIN}" --namespace "${APPLICATION_NAMESPACE}" rollout status \
  deployment/argocd-repo-server --timeout=180s

application_manifest="${OUTPUT_DIRECTORY}/releaseguard-local-application.yaml"
sed "s|repoURL: https://github.com/WeiHanTu/ReleaseGuard.git|repoURL: ${git_repository_url}|" \
  "${REPOSITORY_ROOT}/config/argocd/releaseguard-local.yaml" >"${application_manifest}"

log "Bootstrapping the Argo CD Application from the first Git revision"
"${KUBECTL_BIN}" apply -f "${application_manifest}" >/dev/null
wait_for_revision_and_configuration "${initial_revision}" "initial" 180
"${KUBECTL_BIN}" --namespace "${RELEASE_NAMESPACE}" wait \
  --for=create rollout/${RELEASE_PLAN} --timeout=90s
tracking_id="$("${KUBECTL_BIN}" --namespace "${RELEASE_NAMESPACE}" get \
  releaseplan "${RELEASE_PLAN}" \
  -o jsonpath='{.metadata.annotations.argocd\.argoproj\.io/tracking-id}')"
[[ "${tracking_id}" == *"${APPLICATION_NAME}"* ]]

updated_plan="${git_source}/environments/local/releaseplan.yaml"
awk '
  BEGIN { changed = 0 }
  !changed && $0 == "    delivery.weihantu.dev/configuration-revision: initial" {
    print "    delivery.weihantu.dev/configuration-revision: updated"
    changed = 1
    next
  }
  { print }
  END { if (!changed) exit 1 }
' "${updated_plan}" >"${updated_plan}.next"
mv "${updated_plan}.next" "${updated_plan}"
git -C "${git_source}" add environments/local/releaseplan.yaml
git -C "${git_source}" \
  -c user.name=ReleaseGuard -c user.email=releaseguard@example.invalid \
  commit -m "Update the Git-authored release configuration" >/dev/null
updated_revision="$(git -C "${git_source}" rev-parse HEAD)"

log "Pushing a Git-only desired-state change"
git_sync_started="$(date +%s)"
git -C "${git_source}" push origin main >/dev/null
wait_for_revision_and_configuration "${updated_revision}" "updated" 180
git_sync_seconds=$(($(date +%s) - git_sync_started))

log "Injecting unauthorized live drift into the Git-owned ReleasePlan"
"${KUBECTL_BIN}" --namespace "${RELEASE_NAMESPACE}" label --overwrite \
  releaseplan "${RELEASE_PLAN}" \
  delivery.weihantu.dev/configuration-revision=unauthorized >/dev/null
[[ "$(plan_configuration_revision)" == "unauthorized" ]]
drift_started="$(date +%s)"
wait_for_configuration "updated" 120
drift_recovery_seconds=$(($(date +%s) - drift_started))
[[ "$(application_revision)" == "${updated_revision}" ]]
[[ "$(application_sync_status)" == "Synced" ]]

if "${KUBECTL_BIN}" --namespace "${APPLICATION_NAMESPACE}" logs \
  statefulset/argocd-application-controller | \
  grep -Eq 'level=fatal|panic:'; then
  echo "Argo CD application-controller log contains a fatal error" >&2
  exit 1
fi

cat >"${EVIDENCE_FILE}" <<EOF
kind_version=$(${KIND_BIN} version)
kubernetes_version=$(${KUBECTL_BIN} version -o json | tr -d '\n')
argocd_version=v3.5.2
argocd_install_sha256=9a87f2b3e14c278f12501eb0ef5c3955b27cf05370ca425381c6a908cf85a5c5
git_server_base=alpine/git:2.52.0@sha256:4a0e72d49596a1f5d3701aeedafdadc5c0da4062be4657c7bdc4017387f591cc
git_server_user=65534:65534
initial_git_revision=${initial_revision}
updated_git_revision=${updated_revision}
application_sync_status=Synced
git_authored_configuration=updated
git_sync_seconds=${git_sync_seconds}
injected_live_configuration=unauthorized
recovered_live_configuration=updated
drift_recovery_seconds=${drift_recovery_seconds}
rollout_created=true
imperative_deployment_after_git_push=false
EOF
