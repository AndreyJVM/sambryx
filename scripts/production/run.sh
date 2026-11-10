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
if ! command -v jq >/dev/null 2>&1; then
    echo "Install jq to check production TLS and known_hosts files before starting containers." >&2
    exit 1
fi
if ! "${compose[@]}" config --quiet >/dev/null 2>&1; then
    echo "Compose configuration validation failed. Check your production configuration." >&2
    exit 1
fi
if ! images=$("${compose[@]}" config --images 2>/dev/null); then
    echo "Compose image resolution failed. Containers were not started." >&2
    exit 1
fi
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
if ! model=$("${compose[@]}" config --format json 2>/dev/null); then
    echo "Compose model resolution failed. Containers were not started." >&2
    exit 1
fi
if ! paths=$(jq -er '
    [([.services.web.volumes[] | select(.target == "/etc/sambryx/known_hosts") | .source]
        | if length == 1 then .[0] else null end),
     .secrets.tls_certificate.file, .secrets.tls_private_key.file]
    | if all(.[]; type == "string" and startswith("/") and (test("[\\r\\n]") | not))
      then .[] else error("Invalid production file paths") end
' <<< "$model" 2>/dev/null); then
    echo "Compose did not resolve the required known_hosts and TLS file paths." >&2
    exit 1
fi
mapfile -t files <<< "$paths"
labels=(known_hosts "TLS certificate" "TLS private key")
for index in 0 1 2; do
    file=${files[$index]}
    if [[ ! -f "$file" || ! -r "$file" || ! -s "$file" ]]; then
        echo "${labels[$index]} must be a readable, non-empty regular file. Containers were not started." >&2
        if [[ $index == 0 ]]; then
            echo "Prepare known_hosts with independently verified SSH host keys; follow docs/ssh-host-keys.md before retrying." >&2
        fi
        exit 1
    fi
done
"${compose[@]}" up --detach --wait --wait-timeout 180 --build
