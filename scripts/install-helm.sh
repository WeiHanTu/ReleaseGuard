#!/usr/bin/env bash

set -euo pipefail

readonly HELM_VERSION="v4.2.4"
readonly REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly INSTALL_DIRECTORY="${HELM_INSTALL_DIR:-${REPOSITORY_ROOT}/.local/bin}"
readonly DESTINATION="${INSTALL_DIRECTORY}/helm"

os="$(uname -s | tr '[:upper:]' '[:lower:]')"
case "$(uname -m)" in
  x86_64) arch="amd64" ;;
  arm64 | aarch64) arch="arm64" ;;
  *)
    echo "Unsupported architecture: $(uname -m)" >&2
    exit 1
    ;;
esac

case "${os}-${arch}" in
  darwin-arm64) expected_sha="d747eb4e28bd2727173d15b759fa0a17822291ec09db7ced3d55af290a3661a2" ;;
  darwin-amd64) expected_sha="6c163d687ca03c3b5c01928e53bbbcf9518278f47ce7a2f249a5a08e8bdaa2bc" ;;
  linux-amd64) expected_sha="c306b46f719b0a4da32d0f78ee21bf90ce8d602f15b22ab753f0674d1670a7f3" ;;
  linux-arm64) expected_sha="564de2191b881e9f71b5606b25345821ea1682f06ab90499d3ab22b530176da1" ;;
  *)
    echo "Unsupported platform: ${os}-${arch}" >&2
    exit 1
    ;;
esac

if [[ -x "${DESTINATION}" ]] && "${DESTINATION}" version --short | grep -Fq "${HELM_VERSION}"; then
  echo "Helm ${HELM_VERSION} is already installed at ${DESTINATION}"
  exit 0
fi

temporary_directory="$(mktemp -d)"
trap 'rm -rf "${temporary_directory}"' EXIT
archive="${temporary_directory}/helm.tar.gz"
url="https://get.helm.sh/helm-${HELM_VERSION}-${os}-${arch}.tar.gz"

curl --fail --location --silent --show-error "${url}" --output "${archive}"
if command -v sha256sum >/dev/null 2>&1; then
  actual_sha="$(sha256sum "${archive}" | awk '{print $1}')"
else
  actual_sha="$(shasum -a 256 "${archive}" | awk '{print $1}')"
fi

if [[ "${actual_sha}" != "${expected_sha}" ]]; then
  echo "Helm checksum mismatch: expected ${expected_sha}, got ${actual_sha}" >&2
  exit 1
fi

tar -xzf "${archive}" -C "${temporary_directory}"
mkdir -p "${INSTALL_DIRECTORY}"
install -m 0755 "${temporary_directory}/${os}-${arch}/helm" "${DESTINATION}"
echo "Installed Helm ${HELM_VERSION} at ${DESTINATION}"
