#!/usr/bin/env bash
# Print the sole verified application JAR path for command substitution.
set -euo pipefail
if [[ $# -ne 1 || ! -d "$1" ]]; then
  echo "Usage: select-jar.sh DIRECTORY" >&2
  exit 1
fi
jars=("$1"/*.jar)
if [[ ${#jars[@]} -ne 1 || ! -f "${jars[0]}" ]]; then
  echo "Expected exactly one application JAR in the supplied directory" >&2
  exit 1
fi
script_directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
python3 "$script_directory/verify-artifacts.py" "${jars[0]}" >&2
printf '%s\n' "${jars[0]}"
