#!/usr/bin/env bash
# Локальный клиент + настоящий сервер + TURN relay, без VK/VPS и сети хоста.
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
if [[ ${1:-} != --isolated ]]; then
    exec unshare --user --map-root-user --net bash "$0" --isolated "$(readlink /proc/self/ns/net)"
fi
[[ $# == 2 && "$(readlink /proc/self/ns/net)" != "$2" ]] || {
    echo 'Для теста требуется отдельный network namespace' >&2; exit 1;
}
server=${CSQTT_LOCAL_E2E_SERVER:?Укажите собранный Linux-сервер в CSQTT_LOCAL_E2E_SERVER}
mkdir -p "$root/.scratch"
config=$(mktemp -d "$root/.scratch/local-transport.XXXXXX")
server_pid=
cleanup() {
    if [[ -n $server_pid ]]; then kill "$server_pid" 2>/dev/null || true; wait "$server_pid" 2>/dev/null || true; fi
    rm -rf -- "$config"
}
trap cleanup EXIT
ip link set lo up
ip addr add 198.18.0.1/32 dev lo
export CSQTT_MAIN_PASSWORD=e2e-local-password-20260830
export CSQTT_WEB_PASS=local-test-only-password
export CSQTT_PUBLIC_IP=198.18.0.1
export CSQTT_E2E_PEER=127.0.0.1:46000
export CSQTT_E2E_LOCAL_PROXY=1
"$server" --config-dir "$config" --listen "$CSQTT_E2E_PEER" --web-port 46002 > "$config/server.log" 2>&1 &
server_pid=$!
for _ in {1..50}; do
    if ip addr show csqtt1 >/dev/null 2>&1; then break; fi
    if ! kill -0 "$server_pid" 2>/dev/null; then cat "$config/server.log"; exit 1; fi
    sleep 0.1
done
if ! cargo +1.97.1 test --manifest-path "$root/rust-client/Cargo.toml" --locked \
    windows_client_reaches_wsl_server -- --ignored --nocapture --test-threads=1; then
    cat "$config/server.log"
    exit 1
fi
