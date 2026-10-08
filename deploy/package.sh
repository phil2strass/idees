#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
idee_archive="${1:-/tmp/idee-deploy.tar.gz}"
python3 - "$idee_archive" <<'PY'
from pathlib import Path
import os
import sys
import tarfile
import tempfile

output = Path(sys.argv[1]).resolve()
roots = ['compose.yaml', '.dockerignore', '.gitignore', '.env.example', 'README.md',
         'requirements.txt', 'start-front.sh', 'deploy.sh', 'idee-front', 'idee-service',
         'idee-mcp', 'scripts', 'tests', 'docs', 'deploy']
excluded = {'node_modules', 'target', 'dist', '.angular', '.venv', '__pycache__',
            '.git', '.tunnel', 'backups', 'audits', '.agents', '.codex'}

def sources_only(member):
    parts = Path(member.name).parts
    if any(p in excluded or p == '.env' or (p.startswith('.env.') and p != '.env.example') for p in parts):
        return None
    if member.name.endswith('.log') or Path(member.name).resolve() in {output, Path(temporary)}:
        return None
    if not (member.isfile() or member.isdir()):
        raise SystemExit(f'Archive refusée : fichier spécial ou lien symbolique : {member.name}')
    return member

fd, temporary = tempfile.mkstemp(prefix='.idee-package-', suffix='.tar.gz', dir=output.parent)
os.close(fd)
try:
    with tarfile.open(temporary, 'w:gz') as archive:
        for root in roots:
            archive.add(root, filter=sources_only)
    os.replace(temporary, output)
finally:
    Path(temporary).unlink(missing_ok=True)
print(f'Archive de sources créée : {output}')
PY
