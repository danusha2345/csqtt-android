#!/usr/bin/env python3
"""Проверки настоящих nft/ip/iptables в отдельном user/network namespace.

UCI и logger заменены локальными заглушками. Сеть хоста не изменяется.
"""
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
HOOK = ROOT / 'openwrt/files/usr/libexec/csqtt-tun'
REQUIRE_NETNS = '--require-netns' in sys.argv
if REQUIRE_NETNS:
    sys.argv.remove('--require-netns')


class OpenWrtFirewallTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Root уже имеет CAP_NET_ADMIN; новый user namespace лишает его доступа
        # к каталогам runner с правами 0700 и владельцем вне uid_map.
        cls.namespace_flags = ['-n'] if os.geteuid() == 0 else ['-Urn']
        cls.commands = {name: shutil.which(name) for name in
                        ('unshare', 'sh', 'ip', 'nft', 'iptables', 'awk', 'busybox')}
        missing = [name for name, path in cls.commands.items() if not path]
        if missing:
            reason = 'Нет инструментов: ' + ', '.join(missing)
        else:
            probe = subprocess.run(
                [cls.commands['unshare'], *cls.namespace_flags, cls.commands['sh'], '-ec',
                 'nft add table inet probe; ip link add probe0 type dummy; '
                 'XTABLES_LOCKFILE=/dev/null iptables -t nat -S'],
                capture_output=True, text=True, timeout=15)
            reason = probe.stderr if probe.returncode else ''
        if reason:
            if REQUIRE_NETNS:
                raise RuntimeError(reason)
            raise unittest.SkipTest(reason)

    def setUp(self):
        scratch = ROOT / '.scratch'
        scratch.mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix='openwrt-test-', dir=scratch)
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.bin = self.directory / 'bin'
        self.bin.mkdir()
        for name in ('sh', 'ip', 'awk', 'busybox'):
            (self.bin / name).symlink_to(self.commands[name])
        self.script('uci', '''case "$3" in
csqtt.main.tun_device) echo csqtt0 ;;
csqtt.main.lan_device) echo br-lan ;;
csqtt.main.route_lan) echo "${ROUTE_LAN:-1}" ;;
csqtt.main.route_table) echo 202 ;;
csqtt.main.route_mode) echo "${ROUTE_MODE:-}" ;;
csqtt.main.route_net) echo "${ROUTE_NET:-}" ;;
csqtt.main.route_nets_file) echo "${ROUTE_NETS_FILE:-}" ;;
csqtt.main.route_private) echo "${ROUTE_PRIVATE:-}" ;;
csqtt.main.firewall) echo "${FIREWALL_MODE:-}" ;;
firewall) [ "$2" = show ] && printf '%s\n' "${FIREWALL_SHOW:-}" ;;
*) exit 1 ;;
esac
''')
        self.script('logger', 'printf "%s\\n" "$*" >> "$TEST_LOG"\n')
        self.env = {**os.environ, 'PATH': str(self.bin),
                    'CSQTT_TUN_IP': '10.66.66.2', 'CSQTT_TUN_DNS': '1.1.1.1',
                    'XTABLES_LOCKFILE': str(self.directory / 'xtables.lock'),
                    'TEST_LOG': str(self.directory / 'log')}

    def script(self, name, body):
        path = self.bin / name
        path.write_text('#!/bin/sh\n' + body)
        path.chmod(0o755)

    def backend(self, nft=True, iptables=False, fw4=True):
        if nft:
            self.script('nft', 'if [ "${FAIL_NFT:-0}" = 1 ] && [ "$1" = -f ]; then exit 1; fi\n'
                        f'exec {shlex.quote(self.commands["nft"])} "$@"\n')
        if iptables:
            self.script('iptables', 'if [ "${FAIL_IPTABLES:-0}" = 1 ] && [ "$3" = -I ] '
                        '&& [ "$4" = FORWARD ] && [ "$7" = csqtt0 ]; then exit 1; fi\n'
                        'if [ "${FAIL_DELETE:-0}" = 1 ] && [ "$3" = -D ]; then exit 1; fi\n'
                        f'exec {shlex.quote(self.commands["iptables"])} "$@"\n')
        if fw4:
            self.script('fw4', 'exit 0\n')

    def run_namespace(self, body, fw4=True, shell='sh'):
        setup = '''ip link add csqtt0 type dummy
ip link add br-lan type dummy
ip link set br-lan up
'''
        if fw4:
            setup += '''nft add table inet fw4
nft 'add chain inet fw4 forward { type filter hook forward priority 0; policy drop; }'
nft 'add chain inet fw4 srcnat { type nat hook postrouting priority 100; }'
nft 'add rule inet fw4 forward counter comment "unrelated"'
'''
        hook_shell = 'busybox sh' if shell == 'busybox' else 'sh'
        setup += f'hook() {{ {hook_shell} {shlex.quote(str(HOOK))} "$@"; }}\n'
        setup += '''assert_routes() {
test "$(ip -4 rule show | awk '/12000:.*iif br-lan.*lookup 202/ {n++} END {print n+0}')" = "$1"
test "$(ip -4 route show table all | awk '/default dev csqtt0 table 202/ {n++} END {print n+0}')" = "$1"
}
assert_table() {
test "$(ip -4 route show table 202 | awk '{$1=$1; print}' | busybox sort | busybox tr '\\n' ';')" = "$1"
}
assert_nft_rules() {
test "$(nft -a list table inet fw4 | awk '/comment "csqtt-openwrt"/ {n++} END {print n+0}')" = "$1"
test "$(nft list table inet fw4 | awk '/comment "unrelated"/ {n++} END {print n+0}')" = 1
}
'''
        result = subprocess.run([self.commands['unshare'], *self.namespace_flags, self.commands['sh'],
                                 '-euxc', setup + body], env=self.env, text=True,
                                capture_output=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return Path(self.env['TEST_LOG']).read_text() if Path(self.env['TEST_LOG']).exists() else ''

    def test_nft_repeated_up_down_busybox(self):
        self.backend()
        self.run_namespace('''hook up
hook up
assert_nft_rules 3
assert_routes 1
hook down
hook down
assert_nft_rules 0
assert_routes 0
''', shell='busybox')

    def test_nft_preferred_when_both_installed(self):
        self.backend(iptables=True)
        log = self.run_namespace('''hook up
assert_nft_rules 3
test "$(iptables -S FORWARD)" = '-P FORWARD ACCEPT'
hook down
assert_routes 0
''')
        self.assertIn('firewall backend: nftables', log)

    def test_iptables_repeated_up_down(self):
        self.backend(nft=False, iptables=True, fw4=False)
        self.run_namespace('''hook up
hook up
test "$(iptables -S FORWARD | awk '/csqtt-openwrt/ {n++} END {print n+0}')" = 2
test "$(iptables -t nat -S POSTROUTING | awk '/csqtt-openwrt/ {n++} END {print n+0}')" = 1
assert_routes 1
hook down
hook down
test "$(iptables -S FORWARD)" = '-P FORWARD ACCEPT'
test "$(iptables -t nat -S POSTROUTING)" = '-P POSTROUTING ACCEPT'
assert_routes 0
''', fw4=False, shell='busybox')

    def test_nft_binary_without_fw4_falls_back_to_iptables(self):
        self.backend(iptables=True, fw4=False)
        log = self.run_namespace('hook up\nassert_routes 1\nhook down\nassert_routes 0\n', fw4=False)
        self.assertIn('firewall backend: iptables', log)

    def test_migration_removes_old_iptables_rules(self):
        self.backend(iptables=True, fw4=False)
        self.run_namespace('''hook up
test "$(iptables -S FORWARD | awk '/csqtt-openwrt/ {n++} END {print n+0}')" = 2
nft add table inet fw4
nft 'add chain inet fw4 forward { type filter hook forward priority 0; policy drop; }'
nft 'add chain inet fw4 srcnat { type nat hook postrouting priority 100; }'
nft 'add rule inet fw4 forward counter comment "unrelated"'
hook up
assert_nft_rules 3
test "$(iptables -S FORWARD)" = '-P FORWARD ACCEPT'
test "$(iptables -t nat -S POSTROUTING)" = '-P POSTROUTING ACCEPT'
assert_routes 1
hook down
assert_routes 0
assert_nft_rules 0
''', fw4=False)

    def test_missing_fw4_does_not_fall_back(self):
        self.backend(iptables=True)
        log = self.run_namespace('if hook up; then exit 1; fi\nassert_routes 0\n', fw4=False)
        self.assertIn('start firewall first', log)

    def test_missing_backends(self):
        self.backend(nft=False, fw4=False)
        log = self.run_namespace('if hook up; then exit 1; fi\nassert_routes 0\n', fw4=False)
        self.assertIn('LAN routing requires', log)

    def test_lan_routing_disabled_needs_no_firewall(self):
        self.env['ROUTE_LAN'] = '0'
        self.run_namespace('hook up\nassert_routes 0\nhook down\n', fw4=False)

    def test_nft_failure_rolls_back_routes(self):
        self.backend()
        self.env['FAIL_NFT'] = '1'
        self.run_namespace('if hook up; then exit 1; fi\nassert_routes 0\nassert_nft_rules 0\n')

    def test_iptables_partial_failure_rolls_back(self):
        self.backend(nft=False, iptables=True, fw4=False)
        self.env['FAIL_IPTABLES'] = '1'
        self.run_namespace('''if hook up; then exit 1; fi
assert_routes 0
test "$(iptables -S FORWARD)" = '-P FORWARD ACCEPT'
test "$(iptables -t nat -S POSTROUTING)" = '-P POSTROUTING ACCEPT'
''', fw4=False)

    def test_iptables_delete_failure_does_not_loop_forever(self):
        self.backend(nft=False, iptables=True, fw4=False)
        self.run_namespace('''hook up
export FAIL_DELETE=1
if hook down; then exit 1; fi
assert_routes 0
export FAIL_DELETE=0
hook down
test "$(iptables -S FORWARD)" = '-P FORWARD ACCEPT'
''', fw4=False)

    def test_missing_nat_chain_leaves_no_partial_nft_rules(self):
        self.backend()
        self.run_namespace('''nft delete chain inet fw4 srcnat
if hook up; then exit 1; fi
assert_routes 0
assert_nft_rules 0
''')

    def test_cleanup_preserves_other_interfaces_and_similar_comments(self):
        self.backend()
        self.run_namespace('''nft 'add rule inet fw4 forward iifname "br-other" oifname "csqtt1" accept comment "csqtt-openwrt"'
nft 'add rule inet fw4 forward iifname "br-lan" oifname "csqtt0" accept comment "csqtt-openwrt-other"'
nft 'add rule inet fw4 srcnat oifname "csqtt1" masquerade comment "csqtt-openwrt"'
# Дубликат из скрипта в issue, без meta nfproto.
nft 'add rule inet fw4 forward iifname "br-lan" oifname "csqtt0" accept comment "csqtt-openwrt"'
hook up
assert_nft_rules 5
hook down
assert_nft_rules 2
test "$(nft list table inet fw4 | awk '/csqtt-openwrt-other/ {n++} END {print n+0}')" = 1
''')

    def test_stop_after_firewall_reload(self):
        self.backend()
        self.run_namespace('hook up\nnft delete table inet fw4\nhook down\nassert_routes 0\n')

    def test_exclude_mode_throws_private_and_listed_networks(self):
        self.backend()
        self.env['ROUTE_NET'] = '203.0.113.0/24 198.51.100.7'
        self.run_namespace('''hook up
assert_routes 1
assert_table 'default dev csqtt0 scope link;throw 10.0.0.0/8;throw 169.254.0.0/16;throw 172.16.0.0/12;throw 192.168.0.0/16;throw 198.51.100.7;throw 203.0.113.0/24;'
hook down
assert_table ''
''', shell='busybox')

    def test_exclude_mode_without_private_throws(self):
        self.backend()
        self.env['ROUTE_PRIVATE'] = '0'
        self.run_namespace("hook up\nassert_table 'default dev csqtt0 scope link;'\nhook down\n")

    def test_include_mode_routes_only_listed_networks(self):
        self.backend()
        nets_file = self.directory / 'nets.txt'
        nets_file.write_text('# comment\n\n203.0.113.0/24  # trailing\n  198.51.100.0/25\n')
        self.env.update(ROUTE_MODE='include', ROUTE_NET='192.0.2.0/24', ROUTE_NETS_FILE=str(nets_file))
        self.run_namespace('''hook up
hook up
test "$(ip -4 rule show | awk '/12000:.*iif br-lan.*lookup 202/ {n++} END {print n+0}')" = 1
assert_table '192.0.2.0/24 dev csqtt0 scope link;198.51.100.0/25 dev csqtt0 scope link;203.0.113.0/24 dev csqtt0 scope link;'
assert_nft_rules 3
hook down
assert_table ''
assert_routes 0
assert_nft_rules 0
''', shell='busybox')

    def test_include_mode_with_empty_list_bypasses_tunnel(self):
        self.backend()
        self.env['ROUTE_MODE'] = 'include'
        log = self.run_namespace('''hook up
assert_table ''
test "$(ip -4 rule show | awk '/12000:.*iif br-lan.*lookup 202/ {n++} END {print n+0}')" = 1
assert_nft_rules 3
hook down
''')
        self.assertIn('LAN bypasses the tunnel', log)

    def test_invalid_network_rolls_back(self):
        self.backend()
        self.env.update(ROUTE_MODE='include', ROUTE_NET='203.0.113.0/24 not-a-network')
        self.run_namespace("if hook up; then exit 1; fi\nassert_table ''\nassert_routes 0\nassert_nft_rules 0\n")

    def test_missing_nets_file_and_bad_mode_fail(self):
        self.backend()
        self.env['ROUTE_NETS_FILE'] = str(self.directory / 'missing.txt')
        log = self.run_namespace("if hook up; then exit 1; fi\nassert_table ''\n")
        self.assertIn('cannot read route_nets_file', log)
        del self.env['ROUTE_NETS_FILE']
        self.env['ROUTE_MODE'] = 'both'
        log = self.run_namespace("if hook up; then exit 1; fi\nassert_table ''\n")
        self.assertIn('route_mode must be', log)

    def test_zone_mode_needs_no_backend_and_removes_stale_rules(self):
        self.backend(nft=False, fw4=False)
        self.env.update(FIREWALL_MODE='zone',
                        FIREWALL_SHOW="firewall.@zone[1].name='wan'\nfirewall.@zone[1].device='eth1' 'csqtt0'")
        log = self.run_namespace('''hook up
assert_routes 1
hook down
assert_routes 0
''', fw4=False, shell='busybox')
        self.assertNotIn('firewall backend', log)
        self.assertNotIn('not a device of any firewall zone', log)
        self.backend()
        self.env['FIREWALL_SHOW'] = "firewall.@zone[1].device='csqtt01'"
        log = self.run_namespace('''nft 'add rule inet fw4 forward iifname "br-lan" oifname "csqtt0" accept comment "csqtt-openwrt"'
hook up
assert_nft_rules 0
assert_routes 1
hook down
assert_routes 0
''')
        self.assertIn('csqtt0 is not a device of any firewall zone', log)

    def test_bad_firewall_mode_fails(self):
        self.backend()
        self.env['FIREWALL_MODE'] = 'static'
        log = self.run_namespace("if hook up; then exit 1; fi\nassert_routes 0\n")
        self.assertIn('firewall must be', log)

    def test_unsafe_interface_rejected_before_nft_batch(self):
        self.backend()
        self.env['CSQTT_TUN_DEVICE'] = 'x"; flush ruleset; #'
        self.run_namespace('if hook up; then exit 1; fi\nassert_routes 0\nassert_nft_rules 0\n')


if __name__ == '__main__':
    unittest.main()
