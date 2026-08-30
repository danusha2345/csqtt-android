#!/usr/bin/env bash
set -euo pipefail

repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
version=$(sed -n 's/^version = "\([^"]*\)"/\1/p' "$repo_dir/rust-client/Cargo.toml" | head -n 1)
output_dir=${OPENWRT_OUTPUT_DIR:-$repo_dir/target/openwrt-packages}

declare -A openwrt_arch=(
    [x86_64-unknown-linux-musl]=x86_64
    [aarch64-unknown-linux-musl]=aarch64_generic
    [armv7-unknown-linux-musleabihf]=arm_cortex-a7_neon-vfpv4
)

if [[ $# -eq 0 ]]; then
    targets=(
        x86_64-unknown-linux-musl
        aarch64-unknown-linux-musl
        armv7-unknown-linux-musleabihf
    )
else
    targets=("$@")
fi

command -v cargo-zigbuild >/dev/null || {
    echo "cargo-zigbuild is required: cargo install cargo-zigbuild" >&2
    exit 1
}

for target in "${targets[@]}"; do
    architecture=${openwrt_arch[$target]:-}
    [[ -n "$architecture" ]] || {
        echo "unsupported target: $target" >&2
        exit 2
    }
    cargo +1.97.1 zigbuild \
        --manifest-path "$repo_dir/rust-client/Cargo.toml" \
        --release --locked --target "$target"
    "$repo_dir/openwrt/package.sh" \
        "$repo_dir/rust-client/target/$target/release/client" \
        "$architecture" "$version" "$output_dir"
done
