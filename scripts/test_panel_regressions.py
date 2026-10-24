#!/usr/bin/env python3
"""Регрессии API; запускать через test_panel_regressions.sh в отдельной сети/БД."""
import copy
import http.client
import json
import os
import ssl
import urllib.error
import urllib.request

BASE = 'https://127.0.0.1:46002'
PANEL = '/review-gate'
PANEL_KEY = 'local-panel-regression-password'
MASTER = 'local-master-regression-password'
CONTEXT = ssl._create_unverified_context()  # Изолированный тестовый self-signed сервер.


def call(path, method='GET', data=None, key=PANEL_KEY, bearer=False):
    headers = {'Content-Type': 'application/json'}
    if key:
        headers['Authorization' if bearer else 'X-API-Key'] = ('Bearer ' if bearer else '') + key
    req = urllib.request.Request(BASE + path, method=method, headers=headers,
                                 data=None if data is None else json.dumps(data).encode())
    try:
        with urllib.request.urlopen(req, context=CONTEXT, timeout=10) as response:
            raw = response.read().decode()
            try:
                return response.status, json.loads(raw)
            except ValueError:
                return response.status, raw
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()
    except (http.client.RemoteDisconnected, ConnectionResetError, BrokenPipeError):
        return 0, ''  # Stealth route intentionally aborts unknown requests.


def api(path, *args, **kwargs):
    return call(PANEL + '/api' + path, *args, **kwargs)


def main():
    assert os.environ.get('CSQTT_PANEL_TEST_ISOLATED') == '1', 'Use the isolated shell harness'
    status, before = api('/export')
    assert status == 200
    status, group = api('/groups', 'POST', {'name': 'Regression group'})
    assert status == 200
    status, admin = api('/groups/' + group['id'] + '/admin-link', 'POST', {})
    assert status == 200
    prefix = '/api/group-admin/' + admin['admin_uuid']
    status, client = call(prefix + '/clients', 'POST', {'name': 'Original', 'days': 1, 'workers': 3}, key=None)
    assert status == 200
    password = client['password']
    _, data = call(prefix + '/data', key=None)
    original = next(c for c in data['clients'] if c['password'] == password)
    for invalid in [{'name': 'Rejected workers', 'days': 10, 'workers': 1},
                    {'name': 'Rejected hash', 'days': 10, 'hash': 'invalid'}]:
        status, _ = call(prefix + '/clients/' + password, 'PUT', invalid, key=None)
        assert status == 400
        _, data = call(prefix + '/data', key=None)
        current = next(c for c in data['clients'] if c['password'] == password)
        assert current['name'] == original['name'] and current['expires'] == original['expires']
    print('PASS rejected group update is atomic')

    _, exported = api('/export')
    source = copy.deepcopy(exported)
    source['clients'] = []
    for i, ip in enumerate([exported['clients'][0]['ip'], '10.66.67.2', '8.8.8.8']):
        c = copy.deepcopy(exported['clients'][0])
        c.update(password=f'import-client-{i}', name=f'Imported {i}', ip=ip, sub_uuid='')
        source['clients'].append(c)
    status, _ = api('/import', 'POST', {'config': source, 'import_settings': False})
    assert status == 200
    _, after = api('/export')
    ips = [c['ip'] for c in after['clients']]
    assert len(ips) == len(set(ips))
    assert all(ip.startswith('10.77.88.') and 3 <= int(ip.split('.')[-1]) <= 250 for ip in ips)
    print('PASS duplicate, reserved and foreign IPs reassigned in configured subnet')

    invalid = copy.deepcopy(source)
    invalid['clients'][0]['password'] = 'accepted-before-invalid'
    invalid['clients'][1]['password'] = 'bad|password'
    status, _ = api('/import', 'POST', {'config': invalid, 'import_settings': False})
    assert status == 400
    _, unchanged = api('/export')
    assert unchanged['clients'] == after['clients']
    for path, data in [('/clients', {'name': 'Bad', 'password': 'bad|password', 'workers': 3, 'days': 0}),
                       ('/settings/master-password', {'password': 'bad|password'}),
                       ('/settings', {'main_password': 'bad\npassword'})]:
        status, _ = api(path, 'POST', data)
        assert status == 400, (path, status)
    print('PASS password validation and import atomicity')

    # Fill in bounded requests: the API intentionally caps HTTP body size.
    for start in range(0, 247 - len(after['clients']), 12):
        fill = copy.deepcopy(source)
        fill['clients'] = []
        for i in range(start, min(start + 12, 247 - len(after['clients']))):
            c = copy.deepcopy(source['clients'][0])
            c.update(password=f'fill-{i}', name=f'Fill {i}', ip='')
            fill['clients'].append(c)
        assert api('/import', 'POST', {'config': fill, 'import_settings': False})[0] == 200
    _, filled = api('/export')
    full = copy.deepcopy(source)
    full['clients'] = []
    for i in range(2):
        c = copy.deepcopy(source['clients'][0])
        c.update(password=f'overflow-{i}', name=f'Overflow {i}', ip='')
        full['clients'].append(c)
    status, _ = api('/import', 'POST', {'config': full, 'import_settings': False})
    assert status == 400
    _, unchanged = api('/export')
    assert unchanged['clients'] == filled['clients']
    print('PASS exhausted IP pool rolls back complete import')

    for path in ['/manifest.webmanifest', '/sw.js']:
        _, body = call(path, key=None)
        assert 'review-gate' not in str(body) and 'start_url' not in str(body)
    status, manifest = call(PANEL + '/manifest.webmanifest', key=None)
    assert status == 200 and manifest['scope'] == PANEL + '/'
    print('PASS public PWA endpoints do not disclose secret panel path')

    new_key = 'rotated-master-regression-password'
    assert api('/settings', key=MASTER)[0] == 200
    assert api('/settings/master-password', 'POST', {'password': new_key})[0] == 200
    for bearer in [False, True]:
        assert api('/settings', key=MASTER, bearer=bearer)[0] == 401
        assert api('/settings', key=new_key, bearer=bearer)[0] == 200
    print('PASS master password rotation revokes old header and bearer keys immediately')


if __name__ == '__main__':
    main()
