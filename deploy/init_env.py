#!/usr/bin/env python3
"""Create native deployment configuration once; never overwrite existing credentials."""
from pathlib import Path
import os
import secrets
root = Path(__file__).resolve().parent
text = (root / '.env.example').read_text()
text = text.replace('DB_PASSWORD=\n', 'DB_PASSWORD=' + secrets.token_hex(32) + '\n')
text = text.replace('IDEE_IMPORT_TOKEN=\n', 'IDEE_IMPORT_TOKEN=' + secrets.token_hex(32) + '\n')
fd = os.open(root / '.env', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, 'w') as output:
    output.write(text)
print('deploy/.env créé. Configurer PostgreSQL avec les mêmes identifiants ; aucun compte ni base créé automatiquement.')
