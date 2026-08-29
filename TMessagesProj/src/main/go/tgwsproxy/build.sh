#!/usr/bin/env bash
# Build the TG WS JNI bridge as Android shared libraries consumed by Gradle.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
jni_dir="$(cd "$here/../../../../jni" && pwd)"

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
    [ -n "$sdk" ] || { echo "Set ANDROID_NDK_HOME or ANDROID_SDK_ROOT" >&2; exit 1; }
    ANDROID_NDK_HOME="$(ls -d "$sdk"/ndk/*/ 2>/dev/null | sort -V | tail -1)"
    [ -n "$ANDROID_NDK_HOME" ] || { echo "No NDK found under $sdk/ndk" >&2; exit 1; }
fi

case "$(uname -s)" in
    Linux*)   host=linux-x86_64 ;;
    Darwin*)  host=darwin-x86_64 ;;
    *)        host=windows-x86_64 ;;
esac
toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$host/bin"
[ -d "$toolchain" ] || { echo "Toolchain not found: $toolchain" >&2; exit 1; }

api=27

build_abi() {
    local abi="$1" goarch cc
    case "$abi" in
        arm64-v8a) goarch=arm64; cc="aarch64-linux-android${api}-clang" ;;
        x86_64)    goarch=amd64; cc="x86_64-linux-android${api}-clang" ;;
        *) echo "Unsupported ABI: $abi" >&2; return 1 ;;
    esac
    [ -x "$toolchain/$cc" ] || cc="$cc.cmd"
    echo "==> TG WS $abi ($goarch)"
    mkdir -p "$jni_dir/$abi"
    (
        cd "$here"
        CGO_ENABLED=1 GOOS=android GOARCH="$goarch" CC="$toolchain/$cc" \
        go build -trimpath -buildmode=c-shared \
            -ldflags="-s -w -buildid=" \
            -o "$jni_dir/$abi/libtgwsproxy.so" .
    )
    rm -f "$jni_dir/$abi/libtgwsproxy.h"
    test -s "$jni_dir/$abi/libtgwsproxy.so"
    sha256sum "$jni_dir/$abi/libtgwsproxy.so"
}

if [ $# -gt 0 ]; then
    for abi in "$@"; do build_abi "$abi"; done
else
    build_abi arm64-v8a
    build_abi x86_64
fi
