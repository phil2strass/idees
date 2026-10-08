#!/usr/bin/env python3
"""Configure the native import timer. Private JSON stdin: {"apiKey":"..."}."""
import json
import os
from pathlib import Path
import subprocess
import sys
root = Path(__file__).resolve().parents[1]
key = json.load(sys.stdin)['apiKey']
if not key or any(char in key for char in '\r\n'):
    raise SystemExit('Clé DATAtourisme absente ou invalide.')
path = root / 'deploy/.env.datatourisme'
fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
os.fchmod(fd, 0o600)
with os.fdopen(fd, 'w') as output:
    # JSON quoting is also accepted by the shared dotenv parser.
    output.write('DATATOURISME_API_KEY=' + json.dumps(key) + '\n')
subprocess.run(['python3', str(root / 'deploy/install-services.py')], check=True)
subprocess.run(['sudo', '-n', 'systemctl', 'enable', '--now', 'idee-import.timer'], check=True)
print('Timer quotidien natif activé (04:00 UTC).')
