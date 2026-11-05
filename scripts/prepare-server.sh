#!/usr/bin/env bash
set -euo pipefail
out=${1:?output directory required}
mkdir -p "$out"
expected=764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536
file="$out/scrcpy-server.jar"
if [[ -f "$file" ]] && [[ $(sha256sum "$file" | cut -d' ' -f1) == "$expected" ]]; then exit 0; fi
curl --fail --location --retry 3 --output "$file.download" \
  https://github.com/Genymobile/scrcpy/releases/download/v5.0.1/scrcpy-server-v5.0.1
printf '%s  %s\n' "$expected" "$file.download" | sha256sum --check
mv "$file.download" "$file"
