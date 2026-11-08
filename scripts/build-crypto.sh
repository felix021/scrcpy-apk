#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:?Set ANDROID_HOME or ANDROID_SDK_ROOT}}"
ndk="${ANDROID_NDK_HOME:-$sdk/ndk/28.0.13004108}"
cmake="${SCRCPY_CMAKE:-$sdk/cmake/3.31.6/bin/cmake}"
ninja="${SCRCPY_NINJA:-$sdk/cmake/3.31.6/bin/ninja}"
source_dir=$(python3 scripts/prepare-crypto.py)
stamp="$(python3 -c 'import hashlib; print(hashlib.sha256(open("scripts/build-crypto.sh","rb").read()).hexdigest())')-$(cat "$ndk/source.properties")"
for abi in arm64-v8a armeabi-v7a x86_64 x86; do
    output="build/boringssl/$abi"
    if [ -f "$output/.complete" ] && [ "$(cat "$output/.complete")" = "$stamp" ]; then continue; fi
    "$cmake" -S "$source_dir" -B "$output" -G Ninja \
        -DCMAKE_MAKE_PROGRAM="$ninja" -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static \
        -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DBUILD_TESTING=OFF \
        "-DCMAKE_C_FLAGS=-ffile-prefix-map=$PWD=." "-DCMAKE_CXX_FLAGS=-ffile-prefix-map=$PWD=."
    "$cmake" --build "$output" --target ssl crypto --parallel "${SCRCPY_CRYPTO_JOBS:-2}"
    printf '%s\n' "$stamp" > "$output/.complete"
done
