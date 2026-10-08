#!/usr/bin/env python3
"""Private interactive setup over SSH."""
import getpass
import os
from pathlib import Path
import re
import subprocess
root = Path(__file__).resolve().parents[1]
state = root / '.tunnel'
os.umask(0o077)
tunnel_id = input('Identifiant du tunnel (tunnel_...) : ').strip()
if not re.fullmatch(r'tunnel_[A-Za-z0-9_-]+', tunnel_id):
    raise SystemExit('Identifiant de tunnel invalide')
key = getpass.getpass('Cle API OpenAI runtime (saisie masquee) : ').strip()
if not key.startswith('sk-') or any(c.isspace() for c in key):
    raise SystemExit('Cle invalide')
state.mkdir(mode=0o700, exist_ok=True)
secret = state / 'openai.key'
secret.write_text(key + chr(10))
secret.chmod(0o600)
binary = str(state / 'bin/tunnel-client')
subprocess.run([binary, 'init', '--sample', 'sample_mcp_stdio_local',
    '--profile', 'idee', '--profile-dir', str(state / 'profiles'), '--force',
    '--tunnel-id', tunnel_id, '--control-plane-api-key-ref', 'file:' + str(secret),
    '--health-listen-addr', '127.0.0.1:0',
    '--mcp-command', '/usr/bin/python3 ' + str(root / 'deploy/run-mcp.py')], check=True)
subprocess.run(['sudo', 'systemctl', 'enable', 'idee-tunnel.service'], check=True)
subprocess.run(['sudo', 'systemctl', 'restart', 'idee-tunnel.service'], check=True)
import time
import urllib.request
for attempt in range(30):
    try:
        url = (state / 'health.url').read_text().strip()
        with urllib.request.urlopen(url + '/readyz', timeout=2) as response:
            if response.status == 200:
                print('Tunnel pret. Ajoutez-le maintenant dans ChatGPT (connexion Tunnel).')
                break
    except (OSError, ValueError):
        pass
    time.sleep(1)
else:
    raise SystemExit('Service installe mais tunnel non pret. Verifier la cle, les droits Tunnels Read + Use et les journaux de idee-tunnel.service.')
