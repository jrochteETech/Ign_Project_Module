#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
target="${IGNITION_TARGET:-${1:-8.3}}"
case "$target" in
    8.1|8.3) ;;
    *) printf 'IGNITION_TARGET must be 8.1 or 8.3\n' >&2; exit 1 ;;
esac
branch="${PROJECT_BRANCH:-}"
if [[ -z "$branch" ]]; then
    branch="$(git symbolic-ref --quiet --short HEAD)" || {
        printf 'Supply PROJECT_BRANCH for detached HEAD builds.\n' >&2
        exit 1
    }
fi
commit="$(git rev-parse --verify --quiet HEAD || printf 'uncommitted')"
keystore="${SIGNING_KEYSTORE:-signing/ign-project-module.keystore}"
properties="${SIGNING_PROPERTIES:-signing/signing.properties}"
[[ -f "$keystore" && -f "$properties" ]] || {
    printf 'Set SIGNING_KEYSTORE and SIGNING_PROPERTIES to external files.\n' >&2
    exit 1
}
docker buildx build --progress=plain --target=output --no-cache-filter=signed \
    --output "type=local,dest=${OUTPUT_DIRECTORY:-./dist}" \
    --build-arg "PROJECT_BRANCH=$branch" \
    --build-arg "IGNITION_TARGET=$target" \
    --build-arg "MODULE_VERSION=${MODULE_VERSION:-0.1.0}" \
    --build-arg "GIT_COMMIT=$commit" \
    --build-arg "PROJECT_SOURCE=${PROJECT_SOURCE:-perspective-project}" \
    --secret "id=keystore,src=$keystore" \
    --secret "id=signing_properties,src=$properties" .