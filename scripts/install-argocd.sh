#!/usr/bin/env bash

set -euo pipefail

readonly ARGOCD_VERSION="v3.5.2"
readonly ARGOCD_INSTALL_SHA="9a87f2b3e14c278f12501eb0ef5c3955b27cf05370ca425381c6a908cf85a5c5"
readonly ARGOCD_NAMESPACE="${RELEASEGUARD_ARGOCD_NAMESPACE:-argocd}"
readonly KUBECTL_BIN="${KUBECTL_BIN:-kubectl}"

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

command -v "${KUBECTL_BIN}" >/dev/null
command -v curl >/dev/null

temporary_directory="$(mktemp -d)"
trap 'rm -rf "${temporary_directory}"' EXIT
manifest="${temporary_directory}/argocd-install.yaml"

curl --fail --location --silent --show-error \
  "https://raw.githubusercontent.com/argoproj/argo-cd/${ARGOCD_VERSION}/manifests/install.yaml" \
  --output "${manifest}"
actual_sha="$(sha256 "${manifest}")"
if [[ "${actual_sha}" != "${ARGOCD_INSTALL_SHA}" ]]; then
  echo "Argo CD manifest checksum mismatch: expected ${ARGOCD_INSTALL_SHA}, got ${actual_sha}" >&2
  exit 1
fi

"${KUBECTL_BIN}" create namespace "${ARGOCD_NAMESPACE}" \
  --dry-run=client -o yaml | "${KUBECTL_BIN}" apply -f - >/dev/null
"${KUBECTL_BIN}" apply --server-side \
  --field-manager=releaseguard-bootstrap \
  --namespace "${ARGOCD_NAMESPACE}" \
  --filename "${manifest}" >/dev/null

"${KUBECTL_BIN}" --namespace "${ARGOCD_NAMESPACE}" wait \
  --for=condition=Available deployment --all --timeout=300s
"${KUBECTL_BIN}" --namespace "${ARGOCD_NAMESPACE}" rollout status \
  statefulset/argocd-application-controller --timeout=300s

echo "Installed Argo CD ${ARGOCD_VERSION} from manifest sha256:${ARGOCD_INSTALL_SHA}"
