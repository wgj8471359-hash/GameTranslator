"""Poll GitHub Actions status for the push commit until the run settles."""
import json
import ssl
import sys
import time
import urllib.request

REPO = 'wgj8471359-hash/GameTranslator'
SHA = '5ca8d36'
API = f'https://api.github.com/repos/{REPO}/actions/runs?per_page=10'


def build_opener():
    ctx = ssl.create_default_context()
    try:
        return urllib.request.build_opener(ctx)
    except ssl.SSLError:
        return urllib.request.build_opener(ssl._create_unverified_context())


def get_token():
    import subprocess
    out = subprocess.run(
        [r'F:\Program Files\Git\mingw64\bin\git-credential-manager.exe', 'get'],
        input='protocol=https\nhost=github.com\n', capture_output=True, text=True
    ).stdout
    user = pw = None
    for line in out.splitlines():
        if line.startswith('username='):
            user = line[9:]
        elif line.startswith('password='):
            pw = line[9:]
    return user, pw


def fetch(opener, url, headers):
    req = urllib.request.Request(url, headers=headers)
    with opener.open(req, timeout=30) as resp:
        return json.loads(resp.read().decode())


def main():
    headers = {'Accept': 'application/vnd.github+json', 'User-Agent': 'gt-audit-poll'}
    ctx = ssl._create_unverified_context()
    opener = urllib.request.build_opener(urllib.request.HTTPSHandler(context=ctx))
    try:
        data = fetch(opener, API, headers)
    except Exception as e:
        print(f'public fetch failed: {e}')
        return 2

    runs = data.get('workflow_runs', [])
    target = None
    for r in runs:
        if r.get('head_sha', '').startswith('5ca8d36'):
            target = r
            break
    if target is None:
        print('NO_RUN_FOUND_YET')
        return 1

    run_id = target['id']
    print(f"run {run_id} status={target['status']} conclusion={target['conclusion']}")
    for attempt in range(40):
        time.sleep(20)
        try:
            r = fetch(opener, f'https://api.github.com/repos/{REPO}/actions/runs/{run_id}', headers)
        except Exception as e:
            print(f'poll error: {e}')
            continue
        print(f"poll {attempt}: {r['status']} {r['conclusion']}", flush=True)
        if r['status'] == 'completed':
            print(f"FINAL: {r['conclusion']}")
            return 0 if r['conclusion'] == 'success' else 2
    print('TIMEOUT')
    return 3


if __name__ == '__main__':
    sys.exit(main())
