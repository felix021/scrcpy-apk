#!/usr/bin/env bash
set -euo pipefail
scrcpy_sdk_root=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}
# The emulator can apply the host proxy below Android's network stack, including
# raw ADB TCP. Keep local-network validation independent of ambient host proxies.
exec env -u http_proxy -u HTTP_PROXY -u https_proxy -u HTTPS_PROXY -u all_proxy -u ALL_PROXY \
  "$scrcpy_sdk_root/emulator/emulator" -avd "${1:?AVD name required}" -port "${2:?AVD console port required}" \
  -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect
