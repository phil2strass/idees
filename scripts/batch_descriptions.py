#!/usr/bin/env python3
"""Submit one bounded local Mistral batch or collect only a previously submitted batch."""
import argparse
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile
import uuid

parser = argparse.ArgumentParser(description=__doc__)
group = parser.add_mutually_exclusive_group()
group.add_argument('--limit', type=int, default=None)
group.add_argument('--batch-id', type=uuid.UUID)
parser.add_argument('--wait', action='store_true', help='Collect this batch for up to 26 hours')
parser.add_argument('--background', action='store_true', help='Run in background, logging to a private temporary file')
args = parser.parse_args()
limit = args.limit if args.limit is not None else 500
if not 1 <= limit <= 500:
    parser.error('--limit must be between 1 and 500')
root = Path(__file__).resolve().parents[1]
env = os.environ.copy()
allowed = {'DB_HOST', 'DB_PORT', 'DB_NAME', 'DB_USER', 'DB_PASSWORD',
           'MISTRAL_API_KEY', 'MISTRAL_MODEL', 'IDEE_IMPORT_TOKEN'}
private = root / '.env'
if private.exists():
    for line in private.read_text().splitlines():
        if '=' in line and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            if key.strip() in allowed:
                env[key.strip()] = ' '.join(shlex.split(value))
if not env.get('MISTRAL_API_KEY'):
    raise SystemExit('MISTRAL_API_KEY absente de la configuration privée.')
for key in ('MISTRAL_BATCH_LIMIT', 'MISTRAL_BATCH_ID', 'MISTRAL_REWRITE_SLUG'):
    env.pop(key, None)
env.update(DB_CONTEXTS='production', DATATOURISME_ENABLED='false', DATATOURISME_API_KEY='',
           DATATOURISME_BATCH='false', MISTRAL_AUTO_ENABLED='false',
           MISTRAL_BATCH_WAIT=str(args.wait).lower())
if args.batch_id:
    env['MISTRAL_BATCH_ID'] = str(args.batch_id)
else:
    env['MISTRAL_BATCH_LIMIT'] = str(limit)
jar = root / 'idee-service/target/idee-service-0.0.1-SNAPSHOT.jar'
if not jar.exists():
    raise SystemExit('Construire le service : mvn -f idee-service/pom.xml package')
if args.background:
    with tempfile.NamedTemporaryFile(prefix='idee-mistral-batch-', suffix='.log', delete=False) as log:
        # The child owns the runtime copy and removes it after Java has exited.
        command = [sys.executable, str(Path(__file__).resolve()),
                   *[arg for arg in sys.argv[1:] if arg != '--background']]
        process = subprocess.Popen(command, cwd=root, env=env, stdin=subprocess.DEVNULL,
                                   stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        print(f'PID={process.pid}\nLOG={log.name}', flush=True)
else:
    with tempfile.TemporaryDirectory(prefix='idee-batch-runtime-') as runtime:
        runtime_jar = Path(runtime) / 'service.jar'
        shutil.copyfile(jar, runtime_jar)
        raise SystemExit(subprocess.run(['java', '-jar', str(runtime_jar)], cwd=root, env=env).returncode)
