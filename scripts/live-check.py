"""Opt-in integration checks against .env's real Supabase and running local server.
Creates two disposable QA users and deletes every test banquet it creates.
No service key, no third-party package. Never prints passwords/JWTs/cookies.
Run with ADMIN_PASSWORD set, or enter it privately at the prompt.
"""
import getpass
import http.cookiejar
import json
import os
import pathlib
import re
import secrets
import urllib.error
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
ENV = dict(line.strip().split('=', 1) for line in (ROOT / '.env').read_text(encoding='utf-8').splitlines()
           if '=' in line and not line.strip().startswith('#'))
BASE = os.getenv('APP_URL', ENV.get('APP_URL', 'http://localhost:8080')).rstrip('/')
SUPABASE = os.getenv('SUPABASE_URL', ENV['SUPABASE_URL']).rstrip('/')
KEY = os.getenv('SUPABASE_PUBLISHABLE_KEY', ENV['SUPABASE_PUBLISHABLE_KEY'])
ADMIN_PASSWORD = os.getenv('ADMIN_PASSWORD') or getpass.getpass('Пароль администратора: ')
passed = []


def check(name, condition):
    if not condition:
        raise AssertionError(name)
    passed.append(name)
    print('PASS:', name)


class Browser:
    def __init__(self):
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))

    def request(self, path, data=None, headers=None):
        payload = None if data is None else urllib.parse.urlencode(data).encode()
        req = urllib.request.Request(BASE + path, data=payload, headers=headers or {})
        try:
            with self.opener.open(req, timeout=30) as response:
                return response.status, response.read().decode(), response.headers
        except urllib.error.HTTPError as error:
            return error.code, error.read().decode(), error.headers

    def form(self, page, path, data, **headers):
        _, html, _ = self.request(page)
        csrf = re.search(r'name="csrf"[^>]*value="([^"]+)"', html).group(1)
        return self.request(path, {'csrf': csrf, **data}, headers)


def api(path, method='GET', body=None, jwt=None):
    headers = {'apikey': KEY, 'Content-Type': 'application/json', 'Prefer': 'return=representation'}
    if jwt:
        headers['Authorization'] = 'Bearer ' + jwt
    payload = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(SUPABASE + path, data=payload, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            text = response.read().decode()
            return response.status, json.loads(text) if text else None
    except urllib.error.HTTPError as error:
        text = error.read().decode()
        return error.code, json.loads(text) if text else None


def login_token(login, password):
    email = ENV.get('ADMIN_EMAIL', 'admin26@banketika.example') if login == ENV.get('ADMIN_LOGIN', 'admin 26') else login + '@banketika.example'
    code, result = api('/auth/v1/token?grant_type=password', 'POST', {'email': email, 'password': password})
    check('Auth password grant: ' + login, code == 200 and bool(result.get('access_token')))
    return result


def run():
    suffix = secrets.token_hex(5)
    users = []
    admin = Browser()
    code, html, _ = admin.form('/login', '/login', {'login': 'admin 26', 'password': ADMIN_PASSWORD})
    check('Real admin alias opens admin panel', code == 200 and 'УПРАВЛЕНИЕ ЗАЯВКАМИ' in html)
    admin_token = login_token('admin 26', ADMIN_PASSWORD)['access_token']
    for n in (1, 2):
        login = f'qa_{suffix}_{n}'
        password = secrets.token_urlsafe(18)
        browser = Browser()
        code, html, _ = browser.form('/', '/register', {'login': login, 'password': password,
                                      'full_name': f'Проверка Банкетики {n}', 'phone': '+79990000000'})
        check(f'User {n} registration opens cabinet', code == 200 and 'ЛИЧНЫЙ КАБИНЕТ' in html)
        check(f'User {n} has login and no email field in profile', login in html and 'Email' not in html and '@banketika.example' not in html)
        token = login_token(login, password)
        users.append({'id': token['user']['id'], 'login': login, 'jwt': token['access_token'], 'browser': browser})
    one, two = users
    fields = {'title': 'QA банкет ' + suffix, 'venue': 'QA зал Москва', 'date': '2099-01-01', 'time': '18:30', 'payment': 'card'}
    code, html, _ = one['browser'].form('/banquets/new', '/banquets', {**fields, 'owner_id': two['id'], 'status': 'completed'})
    check('Server forces owner and pending status', code == 200 and 'На рассмотрении' in html)
    code, rows = api('/rest/v1/banquets?title=eq.' + urllib.parse.quote(fields['title']), jwt=one['jwt'])
    row = rows[0]
    check('Real persisted banquet belongs to creator', row['owner_id'] == one['id'] and row['status'] == 'pending')
    check('Moscow event persists as UTC', row['event_at'].startswith('2099-01-01T15:30'))
    check('Other user sees zero foreign banquets', api('/rest/v1/banquets?id=eq.' + row['id'], jwt=two['jwt'])[1] == [])
    check('Other user sees zero foreign profiles', api('/rest/v1/profiles?id=eq.' + one['id'], jwt=two['jwt'])[1] == [])
    check('Anonymous profile read forbidden', api('/rest/v1/profiles')[0] in (401, 403))
    check('User cannot change status directly', api('/rest/v1/banquets?id=eq.' + row['id'], 'PATCH', {'status': 'completed'}, one['jwt'])[1] == [])
    check('User cannot reassign owner directly', api('/rest/v1/banquets?id=eq.' + row['id'], 'PATCH', {'owner_id': two['id']}, one['jwt'])[0] == 403)
    direct = {'owner_id': two['id'], 'title': 'Подмена владельца', 'venue': 'QA Москва', 'event_at': '2099-01-01T15:30:00Z', 'payment_method': 'cash'}
    check('Foreign owner insert forbidden by RLS', api('/rest/v1/banquets', 'POST', direct, one['jwt'])[0] == 403)
    check('Explicit status insert forbidden by column grants', api('/rest/v1/banquets', 'POST', {**direct, 'owner_id': one['id'], 'status': 'completed'}, one['jwt'])[0] == 403)
    check('Past date insert forbidden by database', api('/rest/v1/banquets', 'POST', {**direct, 'owner_id': one['id'], 'event_at': '2000-01-01T00:00:00Z'}, one['jwt'])[0] == 400)
    check('Invalid payment forbidden by database', api('/rest/v1/banquets', 'POST', {**direct, 'owner_id': one['id'], 'payment_method': 'crypto'}, one['jwt'])[0] == 400)
    check('Foreign delete has no effect', api('/rest/v1/banquets?id=eq.' + row['id'], 'DELETE', jwt=two['jwt'])[1] == [])
    check('Profile write forbidden', api('/rest/v1/profiles?id=eq.' + one['id'], 'PATCH', {'login': 'admin 26'}, one['jwt'])[0] == 403)
    api('/auth/v1/user', 'PUT', {'data': {'role': 'admin', 'is_admin': True}}, one['jwt'])
    check('Editable metadata cannot grant admin', api('/rest/v1/rpc/admin_access', 'POST', {}, one['jwt'])[1] is False)
    check('Server admin read forbidden', one['browser'].request('/admin')[0] == 403)
    check('Server admin status forbidden with valid CSRF', one['browser'].form('/cabinet', f"/admin/banquets/{row['id']}/status", {'status': 'completed'})[0] == 403)
    check('Missing CSRF forbidden', one['browser'].request('/banquets', fields)[0] == 403)
    check('Cross origin forbidden', one['browser'].form('/cabinet', '/banquets', fields, Origin='https://evil.example')[0] == 403)
    check('Admin reads all users', len(api('/rest/v1/profiles', jwt=admin_token)[1]) >= 3)
    for state, label in [('active', 'В работе'), ('completed', 'Завершена'), ('pending', 'На рассмотрении')]:
        code, html, _ = admin.form('/admin', f"/admin/banquets/{row['id']}/status", {'status': state})
        check('Admin sets ' + state, code == 200 and label in html)
        check('User sees updated ' + state, label in one['browser'].request('/cabinet')[1])
    code, html, _ = admin.form('/admin', '/admin/banquets', {**fields, 'title': 'QA заявка админа ' + suffix, 'owner_id': two['id']})
    check('Admin creates for another registered user', code == 200)
    created_by_admin = api('/rest/v1/banquets?owner_id=eq.' + two['id'], jwt=admin_token)[1][0]
    check('Selected owner sees admin-created banquet', 'QA заявка админа' in two['browser'].request('/cabinet')[1])
    check('Owner deletes own banquet', one['browser'].form('/cabinet', f"/banquets/{row['id']}/delete", {})[0] == 200)
    check('Admin deletes another user banquet', admin.form('/admin', f"/admin/banquets/{created_by_admin['id']}/delete", {})[0] == 200)
    check('Refresh token works', api('/auth/v1/token?grant_type=refresh_token', 'POST', {'refresh_token': token['refresh_token']})[0] == 200)
    for user in users:
        check('Logout ' + user['login'], user['browser'].form('/cabinet', '/logout', {})[0] == 200)
        check('After logout cabinet requires login', 'С возвращением' in user['browser'].request('/cabinet')[1])
        api('/auth/v1/logout?scope=local', 'POST', jwt=user['jwt'])
    admin.form('/admin', '/logout', {})
    api('/auth/v1/logout?scope=local', 'POST', jwt=admin_token)
    report = {'passed': len(passed), 'checks': passed, 'test_user_ids': [u['id'] for u in users], 'test_logins': [u['login'] for u in users]}
    (ROOT / '.runtime').mkdir(exist_ok=True)
    (ROOT / '.runtime' / 'live-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f'Completed: {len(passed)} checks. QA accounts are listed in .runtime/live-report.json for owner cleanup.')


if __name__ == '__main__':
    run()
