#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
config=${SAMBRYX_ENV_FILE:-"$root/deploy/production/.env"}
if [[ ! -f "$config" ]]; then
    echo "Copy deploy/production/.env.example to deploy/production/.env and configure images and TLS files." >&2
    exit 1
fi
compose=(docker compose --env-file "$config" -f "$root/docker-compose.production.yml")
if [[ ${1:-} == --with-samba ]]; then
    compose+=(-f "$root/docker-compose.samba.yml")
    shift
fi
if [[ $# -ne 0 ]]; then
    echo "Usage: bash scripts/production/run.sh [--with-samba]" >&2
    exit 1
fi
"${compose[@]}" config --quiet
images=$("${compose[@]}" config --images)
if [[ -z "${images//[[:space:]]/}" ]]; then
    echo "Compose did not resolve any images. Containers were not started." >&2
    exit 1
fi
while IFS= read -r image; do
    if [[ "$image" == *REPLACE* || "$image" == *:latest || ( "$image" != *@sha256:* && "${image##*/}" != *:* ) ]]; then
        echo "Select a versioned image tag or digest instead of latest or a placeholder." >&2
        exit 1
    fi
done <<< "$images"
"${compose[@]}" up --detach --wait --wait-timeout 180 --build
