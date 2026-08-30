#!/usr/bin/env bash

set -euo pipefail

readonly REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly IMAGE="${RELEASEGUARD_OPERATOR_IMAGE:-releaseguard-operator:verify}"
readonly MAX_IMAGE_SIZE_BYTES=$((150 * 1024 * 1024))

container_id=""
temporary_directory="$(mktemp -d)"

cleanup() {
  if [[ -n "${container_id}" ]]; then
    docker rm --force "${container_id}" >/dev/null 2>&1 || true
  fi
  rm -rf "${temporary_directory}"
}
trap cleanup EXIT

command -v docker >/dev/null
command -v tar >/dev/null

if [[ "${RELEASEGUARD_SKIP_IMAGE_BUILD:-0}" != "1" ]]; then
  docker build \
    --build-arg "VCS_REF=$(git -C "${REPOSITORY_ROOT}" rev-parse HEAD)" \
    --tag "${IMAGE}" \
    "${REPOSITORY_ROOT}"
fi

user="$(docker image inspect "${IMAGE}" --format '{{.Config.User}}')"
entrypoint="$(docker image inspect "${IMAGE}" --format '{{json .Config.Entrypoint}}')"
command="$(docker image inspect "${IMAGE}" --format '{{json .Config.Cmd}}')"
ports="$(docker image inspect "${IMAGE}" --format '{{json .Config.ExposedPorts}}')"
image_size="$(docker image inspect "${IMAGE}" --format '{{.Size}}')"

[[ "${user}" == "65532:65532" ]]
[[ "${entrypoint}" == '["/usr/bin/java"]' ]]
[[ "${command}" == '["-cp","/app/lib/*","dev.weihantu.releaseguard.operator.ReleaseGuardOperator"]' ]]
[[ "${ports}" == '{"8081/tcp":{}}' ]]
if ((image_size > MAX_IMAGE_SIZE_BYTES)); then
  echo "Image is ${image_size} bytes; expected at most ${MAX_IMAGE_SIZE_BYTES}" >&2
  exit 1
fi

docker run --rm --entrypoint /usr/bin/java "${IMAGE}" -version

container_id="$(docker create "${IMAGE}")"
docker export "${container_id}" --output "${temporary_directory}/rootfs.tar"
tar -tf "${temporary_directory}/rootfs.tar" >"${temporary_directory}/files.txt"

grep -Eq '^app/lib/release-operator-[^/]+\.jar$' "${temporary_directory}/files.txt"
if grep -Eq '(^|/)(bash|sh)$|\.java$|(^|/)gradle(/|$)|(^|/)workspace(/|$)' \
  "${temporary_directory}/files.txt"; then
  echo "Runtime image contains a shell, source, Gradle files, or the build workspace" >&2
  exit 1
fi

printf 'Verified %s: non-root UID/GID 65532, no shell/build tree, %s bytes\n' \
  "${IMAGE}" "${image_size}"
