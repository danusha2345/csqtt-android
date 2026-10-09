#!/usr/bin/env python3
"""Регрессии recovery, отчётов и кросс-сборки; без root и изменения сети."""
import os
import io
import tarfile
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent


def function(text, name):
    start = text.index(name + '() {')
    return text[start:text.index('\n}', start) + 2]


class ReleaseSafetyTests(unittest.TestCase):
    def test_recovery_requires_exact_executable(self):
        text = (ROOT / 'app/src/main/assets/deploy.sh').read_text()
        fn = function(text, 'csqtt_process_is_owned').replace('\\$', '$')
        for executable, expected in [('/usr/bin/python3', 1),
                                     ('/usr/local/lib/csqtt/unrelated', 1),
                                     ('/usr/local/bin/csqtt', 0),
                                     ('/usr/local/bin/csqtt (deleted)', 0),
                                     ('', 1)]:
            # Only the ownership predicate runs; no kill or network commands.
            code = 'CSQTT_CONFIG_DIR=/etc/csqtt\nreadlink() { printf "%s" "$EXE"; }\ntr() { printf "python3 --config-dir /etc/csqtt "; }\n' + fn + '\ncsqtt_process_is_owned "$1"'
            result = subprocess.run(['sh', '-c', code, 'review', str(os.getpid()), '--config-dir', '/etc/csqtt'],
                                    env={**os.environ, 'EXE': executable}, capture_output=True)
            self.assertEqual(result.returncode, expected, executable)

    def test_native_execution_matrix(self):
        fn = function((ROOT / 'rust-server/build_linux.sh').read_text(), 'can_run_target')
        cases = [('x86_64-unknown-linux-gnu', 'amd64', 0),
                 ('aarch64-unknown-linux-gnu', 'amd64', 1),
                 ('x86_64-unknown-linux-gnu', 'arm64', 1),
                 ('aarch64-unknown-linux-gnu', 'arm64', 0),
                 ('armv7-unknown-linux-gnueabihf', 'armv7', 0),
                 ('x86_64-pc-windows-gnu', 'amd64', 1)]
        for host, arch, expected in cases:
            r = subprocess.run(['bash', '-c', fn + '\ncan_run_target "$1" "$2"', 'test', host, arch])
            self.assertEqual(r.returncode, expected, (host, arch))

    def test_openwrt_package_preserves_config(self):
        with tempfile.TemporaryDirectory() as tmp:
            subprocess.run(['bash', str(ROOT / 'openwrt/package.sh'), '/bin/true',
                            'x86_64', 'test', tmp], check=True, capture_output=True)
            ipk = str(Path(tmp) / 'csqtt-client_test_x86_64.ipk')
            control = subprocess.check_output(['ar', 'p', ipk, 'control.tar.gz'])
            with tarfile.open(fileobj=io.BytesIO(control), mode='r:gz') as archive:
                self.assertEqual(archive.extractfile('./conffiles').read(), b'/etc/config/csqtt\n')
                self.assertIn(b'Depends: libc, kmod-tun, ip-full\n',
                              archive.extractfile('./control').read())
            data = subprocess.check_output(['ar', 'p', ipk, 'data.tar.gz'])
            with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as archive:
                self.assertEqual(archive.getmember('./etc/config/csqtt').mode, 0o600)
                self.assertEqual(archive.getmember('./usr/bin/csqtt-client').mode, 0o755)
                self.assertEqual(archive.extractfile('./usr/libexec/csqtt-tun').read(),
                                 (ROOT / 'openwrt/files/usr/libexec/csqtt-tun').read_bytes())

    def test_reports_do_not_collect_secret_sources(self):
        with tempfile.TemporaryDirectory() as tmp:
            mock = Path(tmp) / 'mock'
            mock.write_text(r'''#!/bin/bash
case "${0##*/}" in
 id) echo 0 ;;
 systemctl) case "$1" in cat|status) echo FAKE_SECRET;; show) case "$*" in *ExecStart*|*Environment\ *) echo FAKE_SECRET;; esac;; esac ;;
 journalctl) echo FAKE_SECRET ;;
 ps) case "$*" in *args*|*cmd*) echo FAKE_SECRET;; esac ;;
 docker) case "$*" in *--format*) echo mock-container;; *) echo FAKE_SECRET;; esac ;;
 *) : ;;
esac
''')
            mock.chmod(0o755)
            for name in ['id', 'systemctl', 'journalctl', 'ps', 'docker', 'find', 'pgrep',
                         'hostnamectl', 'uname', 'uptime', 'ss', 'ip', 'sysctl', 'iptables-save',
                         'nft', 'ufw', 'firewall-cmd', 'nstat', 'iptables', 'sqlite3']:
                (Path(tmp) / name).symlink_to(mock)
            for script in ['diagnose_csqtt_server.sh', 'csqtt-network-report.sh']:
                source = (ROOT / 'scripts' / script).read_text().replace('(( EUID != 0 ))', 'false')
                r = subprocess.run(['bash', '-c', source],
                                   env={**os.environ, 'PATH': tmp + ':' + os.environ['PATH']},
                                   capture_output=True, text=True, timeout=20)
                self.assertEqual(r.returncode, 0, r.stderr)
                self.assertNotIn('FAKE_SECRET', r.stdout)


if __name__ == '__main__':
    unittest.main()
