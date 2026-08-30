#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 4 ]]; then
    echo "usage: $0 <binary> <openwrt-arch> <version> <output-dir>" >&2
    exit 2
fi

binary=$1
architecture=$2
version=$3
output_dir=$4
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
work_dir=$(mktemp -d)
trap 'rm -rf -- "$work_dir"' EXIT

test -x "$binary"
mkdir -p "$work_dir/control" "$work_dir/data/usr/bin" \
    "$work_dir/data/etc/config" "$work_dir/data/etc/init.d" \
    "$work_dir/data/usr/libexec" "$output_dir"
install -m 0755 "$binary" "$work_dir/data/usr/bin/csqtt-client"
install -m 0600 "$script_dir/files/etc/config/csqtt" "$work_dir/data/etc/config/csqtt"
install -m 0755 "$script_dir/files/etc/init.d/csqtt" "$work_dir/data/etc/init.d/csqtt"
install -m 0755 "$script_dir/files/usr/libexec/csqtt-tun" "$work_dir/data/usr/libexec/csqtt-tun"

installed_size=$(du -k -s "$work_dir/data" | awk '{print $1}')
cat >"$work_dir/control/control" <<EOF
Package: csqtt-client
Version: $version
Architecture: $architecture
Maintainer: danusha2345
Depends: libc, kmod-tun, ip-full, iptables-nft
Installed-Size: $installed_size
Section: net
Priority: optional
Description: CSQTT client for OpenWrt routers
EOF

printf '2.0\n' >"$work_dir/debian-binary"
tar -C "$work_dir/control" -czf "$work_dir/control.tar.gz" .
tar -C "$work_dir/data" -czf "$work_dir/data.tar.gz" .
package="$output_dir/csqtt-client_${version}_${architecture}.ipk"
ar rcs "$package" "$work_dir/debian-binary" "$work_dir/control.tar.gz" "$work_dir/data.tar.gz"
echo "$package"

install -m 0755 "$script_dir/install.sh" "$work_dir/data/install.sh"
bundle="$output_dir/csqtt-openwrt_${version}_${architecture}.tar.gz"
tar -C "$work_dir/data" -czf "$bundle" .
echo "$bundle"
