#!/usr/bin/env bash

set -euo pipefail

readonly KIND_VERSION="v0.31.0"
readonly REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly INSTALL_DIRECTORY="${KIND_INSTALL_DIR:-${REPOSITORY_ROOT}/.local/bin}"
readonly DESTINATION="${INSTALL_DIRECTORY}/kind"

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
  darwin-arm64) expected_sha="88bf554fe9da6311c9f8c2d082613c002911a476f6b5090e9420b35d84e70c5c" ;;
  darwin-amd64) expected_sha="a8b3cf77b2ad77aec5bf710d1a2589d9117576132af812885cad41e9dede4d4e" ;;
  linux-amd64) expected_sha="eb244cbafcc157dff60cf68693c14c9a75c4e6e6fedaf9cd71c58117cb93e3fa" ;;
  linux-arm64) expected_sha="8e1014e87c34901cc422a1445866835d1e666f2a61301c27e722bdeab5a1f7e4" ;;
  *)
    echo "Unsupported platform: ${os}-${arch}" >&2
    exit 1
    ;;
esac

if [[ -x "${DESTINATION}" ]] && "${DESTINATION}" version | grep -Fq "${KIND_VERSION}"; then
  echo "kind ${KIND_VERSION} is already installed at ${DESTINATION}"
  exit 0
fi

temporary_directory="$(mktemp -d)"
trap 'rm -rf "${temporary_directory}"' EXIT
download="${temporary_directory}/kind"
url="https://github.com/kubernetes-sigs/kind/releases/download/${KIND_VERSION}/kind-${os}-${arch}"

curl --fail --location --silent --show-error "${url}" --output "${download}"
if command -v sha256sum >/dev/null 2>&1; then
  actual_sha="$(sha256sum "${download}" | awk '{print $1}')"
else
  actual_sha="$(shasum -a 256 "${download}" | awk '{print $1}')"
fi

if [[ "${actual_sha}" != "${expected_sha}" ]]; then
  echo "kind checksum mismatch: expected ${expected_sha}, got ${actual_sha}" >&2
  exit 1
fi

mkdir -p "${INSTALL_DIRECTORY}"
install -m 0755 "${download}" "${DESTINATION}"
echo "Installed kind ${KIND_VERSION} at ${DESTINATION}"
