#!/usr/bin/env python3
"""Rewrite exactly one outing using the private local Mistral configuration."""
import argparse
import os
from pathlib import Path
import shlex
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('slug', help='Slug of the single outing to process')
args = parser.parse_args()
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
    raise SystemExit('MISTRAL_API_KEY absente de la configuration privee.')
env['MISTRAL_REWRITE_SLUG'] = args.slug
env['DB_CONTEXTS'] = 'production'
env['DATATOURISME_ENABLED'] = 'false'
jar = root / 'idee-service/target/idee-service-0.0.1-SNAPSHOT.jar'
if not jar.exists():
    raise SystemExit('Construire le service : mvn -f idee-service/pom.xml package')
raise SystemExit(subprocess.run(['java', '-jar', str(jar)], cwd=root, env=env).returncode)
