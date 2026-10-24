#!/usr/bin/env bash
# Настоящий сервер API с частной БД/сетью; production не используется.
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
if [[ ${1:-} != --isolated ]]; then
    exec unshare --user --map-root-user --net bash "$0" --isolated "$(readlink /proc/self/ns/net)"
fi
[[ $# == 2 && "$(readlink /proc/self/ns/net)" != "$2" ]] || exit 1
server=${CSQTT_LOCAL_E2E_SERVER:?Укажите тестовый сервер}
mkdir -p "$root/.scratch"
config=$(mktemp -d "$root/.scratch/panel-regressions.XXXXXX")
server_pid=
cleanup() {
    if [[ -n $server_pid ]]; then kill "$server_pid" 2>/dev/null || true; wait "$server_pid" 2>/dev/null || true; fi
    rm -rf -- "$config"
}
trap cleanup EXIT
ip link set lo up
export CSQTT_MAIN_PASSWORD=local-master-regression-password CSQTT_WEB_PASS=local-panel-regression-password
export CSQTT_PUBLIC_IP=198.18.0.1 CSQTT_TUN_SUBNET=10.77.88.0/24 CSQTT_PANEL_TEST_ISOLATED=1
"$server" --config-dir "$config" --listen 127.0.0.1:46000 --web-port 46002 --web-secret-path review-gate > "$config/server.log" 2>&1 &
server_pid=$!
for _ in {1..100}; do
    if curl -ksf https://127.0.0.1:46002/review-gate/ >/dev/null; then break; fi
    if ! kill -0 "$server_pid" 2>/dev/null; then cat "$config/server.log"; exit 1; fi
    sleep .1
done
ip -j -4 addr show csqtt1 | python3 -c 'import json,sys; assert any(a["local"] == "10.77.88.1" for i in json.load(sys.stdin) for a in i["addr_info"])'
if ! python3 "$root/scripts/test_panel_regressions.py"; then cat "$config/server.log"; exit 1; fi
