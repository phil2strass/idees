#!/usr/bin/env python3
"""Install the dedicated import container and cron on the existing OVH host.

Read {"apiKey": "..."} from private stdin, never command arguments.
"""
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys

root = Path('/home/debian/idee')


def run(*args):
    subprocess.run(args, check=True, stdin=subprocess.DEVNULL)


values = {}
for line in (root / 'deploy/.env').read_text().splitlines():
    if '=' in line and not line.lstrip().startswith('#'):
        key, value = line.split('=', 1)
        values[key.strip()] = ' '.join(shlex.split(value))
api_key = json.load(sys.stdin)['apiKey']
if not api_key or any(c in api_key for c in '\r\n'):
    raise SystemExit('Cle DATAtourisme absente ou invalide.')
names = ['DB_PASSWORD', 'IDEE_IMPORT_TOKEN']
if any(not values.get(name) or any(c in values[name] for c in '\r\n') for name in names):
    raise SystemExit('Secrets de production absents ou invalides.')
private = root / 'deploy/.env.datatourisme'
fd = os.open(private, os.O_CREAT | os.O_WRONLY | os.O_TRUNC, 0o600)
os.fchmod(fd, 0o600)
with os.fdopen(fd, 'w') as out:
    for name in names:
        out.write(name + '=' + values[name] + '\n')
    out.write('DATATOURISME_API_KEY=' + api_key + '\n')

run('docker', 'build', '-t', 'idee-datatourisme:cron', '-f',
    str(root / '.datatourisme-build/idee-service/Dockerfile'),
    str(root / '.datatourisme-build'))
run('sudo', '-n', 'apt-get', 'update', '-qq')
run('sudo', '-n', 'env', 'DEBIAN_FRONTEND=noninteractive', 'apt-get',
    'install', '-y', '--no-install-recommends', 'cron', 'logrotate')
(root / 'logs').mkdir(exist_ok=True, mode=0o750)
(root / 'deploy/datatourisme-cron.sh').chmod(0o755)
run('sudo', '-n', 'install', '-o', 'root', '-g', 'root', '-m', '0644',
    str(root / 'deploy/idee-datatourisme.logrotate'), '/etc/logrotate.d/idee-datatourisme')
run('sudo', '-n', 'install', '-o', 'root', '-g', 'root', '-m', '0644',
    str(root / 'deploy/idee-datatourisme.cron'), '/etc/cron.d/idee-datatourisme')
run('sudo', '-n', 'systemctl', 'enable', '--now', 'cron')
print('Image dediee construite et cron quotidien installe.')
