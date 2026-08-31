#!/usr/bin/env bash
set -euo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
run_checks=1
case "${1:-}" in
  "") ;;
  --tests) run_checks=1 ;;
  --no-tests) run_checks=0 ;;
  *) echo "usage: $0 [--tests|--no-tests]" >&2; exit 2 ;;
esac

sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [[ -z "$sdk" || ! -d "$sdk" ]]; then
  for candidate in "$HOME/Android/Sdk" /opt/android-sdk /usr/lib/android-sdk; do
    if [[ -d "$candidate" ]]; then sdk=$candidate; break; fi
  done
fi
[[ -n "$sdk" && -d "$sdk" ]] || { echo "Android SDK not found" >&2; exit 1; }
ndk=${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}
if [[ -z "$ndk" || ! -d "$ndk" ]]; then
  ndk=$(find "$sdk/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -n 1)
fi
[[ -n "$ndk" && -d "$ndk" ]] || { echo "Android NDK not found" >&2; exit 1; }

export ANDROID_HOME="$sdk"
export ANDROID_SDK_ROOT="$sdk"
export ANDROID_NDK_HOME="$ndk"
export ANDROID_NDK_ROOT="$ndk"
command -v cargo >/dev/null
cargo ndk --version >/dev/null

if [[ "$run_checks" == 1 ]]; then
  cargo +1.97.1 fmt --manifest-path "$root/rust-client/Cargo.toml" --all -- --check
  cargo +1.97.1 clippy --manifest-path "$root/rust-client/Cargo.toml" --all-targets --locked -- -D warnings
  cargo +1.97.1 test --manifest-path "$root/rust-client/Cargo.toml" --all-targets --locked
fi

arm64_target="$root/build/rust-client-android-arm64"
armv7_target="$root/build/rust-client-android-armv7"
cd "$root/rust-client"
CARGO_TARGET_DIR="$arm64_target" cargo +1.97.1 ndk -t arm64-v8a -P 26 \
  build --release --locked
CARGO_TARGET_DIR="$armv7_target" cargo +1.97.1 ndk -t armeabi-v7a -P 26 \
  build --release --locked

mkdir -p "$root/app/src/main/jniLibs/arm64-v8a" "$root/app/src/main/jniLibs/armeabi-v7a"
cp "$arm64_target/aarch64-linux-android/release/client" "$root/app/src/main/jniLibs/arm64-v8a/libclient.so"
cp "$armv7_target/armv7-linux-androideabi/release/client" "$root/app/src/main/jniLibs/armeabi-v7a/libclient.so"
python3 "$root/scripts/native_client_provenance.py" write
python3 "$root/scripts/native_client_provenance.py" verify
