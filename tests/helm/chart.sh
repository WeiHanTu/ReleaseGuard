#!/usr/bin/env bash

set -euo pipefail

readonly REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly CHART="${REPOSITORY_ROOT}/charts/releaseguard"
readonly DEFAULT_HELM="${REPOSITORY_ROOT}/.local/bin/helm"

if [[ -n "${HELM_BIN:-}" ]]; then
  helm_bin="${HELM_BIN}"
elif [[ -x "${DEFAULT_HELM}" ]]; then
  helm_bin="${DEFAULT_HELM}"
else
  helm_bin="$(command -v helm || true)"
fi

if [[ -z "${helm_bin}" ]]; then
  echo "Helm is missing; run ./scripts/install-helm.sh" >&2
  exit 1
fi

temporary_directory="$(mktemp -d)"
trap 'rm -rf "${temporary_directory}"' EXIT
default_manifest="${temporary_directory}/default.yaml"
digest_manifest="${temporary_directory}/digest.yaml"
readonly digest="sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

"${helm_bin}" lint "${CHART}" --strict --kube-version 1.35.0
"${helm_bin}" template releaseguard "${CHART}" \
  --namespace releaseguard-system --include-crds >"${default_manifest}"
"${helm_bin}" template releaseguard "${CHART}" \
  --namespace releaseguard-system \
  --set image.repository=ghcr.io/weihantu/releaseguard-operator \
  --set-string "image.digest=${digest}" \
  --set metricGate.image.repository=ghcr.io/weihantu/releaseguard-metric-gate \
  --set-string "metricGate.image.digest=${digest}" >"${digest_manifest}"

for crd in "${REPOSITORY_ROOT}"/config/crd/*.yml; do
  chart_crd="${CHART}/crds/$(basename "${crd}")"
  cmp "${crd}" "${chart_crd}"
done

grep -Fq 'kind: Deployment' "${default_manifest}"
grep -Fq 'type: Recreate' "${default_manifest}"
grep -Fq 'replicas: 1' "${default_manifest}"
grep -Fq 'runAsNonRoot: true' "${default_manifest}"
grep -Fq 'runAsUser: 65532' "${default_manifest}"
grep -Fq 'readOnlyRootFilesystem: true' "${default_manifest}"
grep -Fq 'allowPrivilegeEscalation: false' "${default_manifest}"
grep -Fq 'type: RuntimeDefault' "${default_manifest}"
grep -Fq 'path: /healthz' "${default_manifest}"
grep -Fq 'path: /readyz' "${default_manifest}"
grep -Fq 'resources: ["releaseplans/status"]' "${default_manifest}"
grep -Fq 'resources: ["metricpolicies"]' "${default_manifest}"
grep -Fq 'resources: ["rollouts/status"]' "${default_manifest}"
grep -Fq 'image: "releaseguard-operator:dev"' "${default_manifest}"
grep -Fq 'name: releaseguard-metric-gate' "${default_manifest}"
grep -Fq 'app.kubernetes.io/component: metric-gate' "${default_manifest}"
grep -Fq 'image: "releaseguard-metric-gate:dev"' "${default_manifest}"
grep -Fq 'automountServiceAccountToken: false' "${default_manifest}"
grep -Fq 'name: RELEASEGUARD_GATE_AUTH_TOKEN' "${default_manifest}"
grep -Fq 'name: "releaseguard-metric-gate-auth"' "${default_manifest}"
grep -Fq 'key: "token"' "${default_manifest}"
grep -Fq 'path: /actuator/health/liveness' "${default_manifest}"
grep -Fq 'path: /actuator/health/readiness' "${default_manifest}"
grep -Fq 'type: ClusterIP' "${default_manifest}"
grep -Fq "image: \"ghcr.io/weihantu/releaseguard-operator@${digest}\"" "${digest_manifest}"
grep -Fq "image: \"ghcr.io/weihantu/releaseguard-metric-gate@${digest}\"" "${digest_manifest}"

if grep -Fq 'kind: Secret' "${default_manifest}"; then
  echo "Chart rendered secret material instead of referencing an existing Secret" >&2
  exit 1
fi

if grep -Eq 'verbs:.*\*|resources:.*\*|apiGroups:.*\*' "${default_manifest}"; then
  echo "Rendered RBAC contains a wildcard" >&2
  exit 1
fi

if "${helm_bin}" template releaseguard "${CHART}" --set replicaCount=2 \
  >"${temporary_directory}/invalid-replicas.out" 2>&1; then
  echo "Chart accepted replicaCount=2 without leader election" >&2
  exit 1
fi

if "${helm_bin}" template releaseguard "${CHART}" \
  --set serviceAccount.create=false --set-string serviceAccount.name= \
  >"${temporary_directory}/missing-service-account.out" 2>&1; then
  echo "Chart accepted an empty external service account name" >&2
  exit 1
fi

if "${helm_bin}" template releaseguard "${CHART}" \
  --set-string metricGate.auth.existingSecret= \
  >"${temporary_directory}/missing-gate-secret.out" 2>&1; then
  echo "Chart accepted an empty metric-gate Secret name" >&2
  exit 1
fi

if "${helm_bin}" template releaseguard "${CHART}" \
  --set-string metricGate.requestTimeout=61s \
  >"${temporary_directory}/invalid-gate-timeout.out" 2>&1; then
  echo "Chart accepted a metric-gate timeout above the service maximum" >&2
  exit 1
fi

printf 'Verified Helm chart with %s: CRD freshness, schema guards, both images, external Secret reference, RBAC, probes, and pod security\n' \
  "$("${helm_bin}" version --short)"
