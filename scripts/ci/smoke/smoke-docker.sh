#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 || ! -f "$2" ]]; then
    echo "Usage: bash scripts/ci/smoke/smoke-docker.sh IMAGE JAR" >&2
    exit 1
fi
image=$1
jar=$2
script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
container=
cleanup() {
    if [[ -n "$container" ]]; then
        docker rm -f "$container" >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

container=$(docker run --detach --read-only \
    --tmpfs /tmp:rw,noexec,nosuid,size=128m \
    --publish 127.0.0.1::8080 "$image")
uid=$(docker exec "$container" id -u)
if [[ "$uid" == 0 ]]; then
    echo "Container must run without root privileges" >&2
    exit 1
fi
expected=$(sha256sum "$jar" | cut -d ' ' -f 1)
actual=$(docker exec "$container" sha256sum /app/app.jar | cut -d ' ' -f 1)
if [[ "$actual" != "$expected" ]]; then
    echo "Container contains a different JAR" >&2
    exit 1
fi
binding=$(docker port "$container" 8080/tcp)
base_url="http://${binding}"
ready=false
for ((attempt = 0; attempt < 60; attempt++)); do
    if curl --fail --silent --max-time 2 "$base_url/api/auth/environment" >/dev/null; then
        ready=true
        break
    fi
    if [[ $(docker inspect --format '{{.State.Running}}' "$container") != true ]]; then
        echo "Smoke container exited before becoming ready" >&2
        exit 1
    fi
    sleep 1
done
if [[ "$ready" != true ]]; then
    echo "Smoke container did not become ready within the startup limit" >&2
    exit 1
fi
python3 "$script_dir/../checks/verify-artifacts.py" "$jar" --url "$base_url"
echo "Docker smoke check passed: non-root UID $uid, identical JAR, UI/assets and remote API"
