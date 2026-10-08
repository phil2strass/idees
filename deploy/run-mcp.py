#!/usr/bin/env python3
"""Start MCP with only its production API credential."""
import os
from pathlib import Path
root = Path(__file__).resolve().parents[1]
values = dict(line.split('=', 1) for line in (root / 'deploy/.env').read_text().splitlines()
              if line and not line.startswith('#') and '=' in line)
token = values['IDEE_IMPORT_TOKEN'].strip().strip(chr(34)).strip(chr(39))
if len(token) < 32:
    raise SystemExit('Missing production import credential')
env = {'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8',
       'IDEE_API_URL': 'https://ideesdesorties.eu', 'IDEE_IMPORT_TOKEN': token}
python = str(root / 'idee-mcp/.venv/bin/python')
os.execve(python, [python, str(root / 'idee-mcp/server.py')], env)
