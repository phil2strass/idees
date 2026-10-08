#!/usr/bin/env python3
"""Run description/import database tests in disposable schemas, with no real provider keys."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import uuid

root = Path(__file__).resolve().parents[1]
env = os.environ.copy()
private = root / '.env'
if private.exists():
    for line in private.read_text().splitlines():
        if '=' in line and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            if key.strip() in {'DB_HOST', 'DB_PORT', 'DB_NAME', 'DB_USER', 'DB_PASSWORD'}:
                env[key.strip()] = ' '.join(shlex.split(value))
env.update(OPENAI_API_KEY='', OPENAI_TRANSLATIONS_ENABLED='false', MISTRAL_AUTO_ENABLED='false', MISTRAL_API_KEY='',
           DATATOURISME_ENABLED='false', DATATOURISME_API_KEY='', DB_CONTEXTS='production',
           PGPASSWORD=env.get('DB_PASSWORD', ''), IDEE_DATATOURISME_DB_TEST='isolated',
           IDEE_CALENDAR_SCRIPT=str(root / 'scripts/expand_calendar_json.py'))
psql = ['psql', '-X', '-v', 'ON_ERROR_STOP=1', '-h', env.get('DB_HOST', 'localhost'),
        '-p', env.get('DB_PORT', '5432'), '-U', env.get('DB_USER', 'htpweb'),
        '-d', env.get('DB_NAME', 'idee')]

for test in ('OutingDescriptionWorkerDatabaseTest', 'DatatourismeDatabaseTest', 'OutingDescriptionsDatabaseTest', 'OutingTranslationsDatabaseTest', 'PublicUrlsDatabaseTest'):
    schema = 'idee_datatourisme_test_' + uuid.uuid4().hex[:12]
    subprocess.run(psql + ['-c', 'CREATE SCHEMA ' + schema], env=env, check=True, capture_output=True)
    env.update(SPRING_DATASOURCE_URL='jdbc:postgresql://' + env.get('DB_HOST', 'localhost') + ':'
               + env.get('DB_PORT', '5432') + '/' + env.get('DB_NAME', 'idee') + '?currentSchema=' + schema,
               SPRING_LIQUIBASE_DEFAULT_SCHEMA=schema)
    try:
        with tempfile.NamedTemporaryFile(prefix='idee-' + test + '-', suffix='.log', delete=False) as log:
            result = subprocess.run(['mvn', '-f', 'idee-service/pom.xml', '-Dtest=' + test, 'test'],
                                    cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT)
            print(test + ': ' + ('OK' if result.returncode == 0 else 'ÉCHEC') + ' ; journal ' + log.name)
    finally:
        subprocess.run(psql + ['-c', 'DROP SCHEMA ' + schema + ' CASCADE'], env=env, check=True, capture_output=True)
    if result.returncode:
        raise SystemExit(result.returncode)
print('Cinq schémas de recette supprimés ; aucun appel réel à OpenAI, Mistral ou DATAtourisme.')
