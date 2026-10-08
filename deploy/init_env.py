#!/usr/bin/env python3
"""Create deployment secrets once, without printing them or overwriting a file."""
from pathlib import Path
import os
import secrets
path = Path(__file__).resolve().parent / '.env'
fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, 'w') as out:
    out.write(f'DB_PASSWORD={secrets.token_hex(32)}\nIDEE_IMPORT_TOKEN={secrets.token_hex(32)}\nIDEE_HTTP_PORT=9081\n')
print('deploy/.env créé avec des secrets distincts. Conserver ce fichier privé.')
