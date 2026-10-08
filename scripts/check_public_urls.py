#!/usr/bin/env python3
"""Verify migration of pre-existing outings in an isolated, rolled-back schema."""
import os
from pathlib import Path
import shlex
import subprocess
import uuid

root = Path(__file__).resolve().parents[1]
env = os.environ.copy()
for line in (root / '.env').read_text().splitlines():
    if '=' in line and not line.lstrip().startswith('#'):
        key, value = line.split('=', 1)
        if key.strip() in {'DB_HOST', 'DB_PORT', 'DB_NAME', 'DB_USER', 'DB_PASSWORD'}:
            env[key.strip()] = ' '.join(shlex.split(value))
env['PGPASSWORD'] = env.get('DB_PASSWORD', '')
schema = 'idee_urls_test_' + uuid.uuid4().hex[:12]
folder = root / 'idee-service/src/main/resources/db/changelog'
statements = ['BEGIN;', 'CREATE SCHEMA ' + schema + ';', 'SET LOCAL search_path TO ' + schema + ';']
for file in sorted(folder.glob('*.sql')):
    if file.name.startswith(('002-', '012-')):
        continue
    if int(file.name[:3]) < 12:
        statements.append(file.read_text())
statements.append("""
INSERT INTO idee_place(name,city,department) VALUES('Musée','Strasbourg','67');
INSERT INTO idee_outing(slug,title,summary,description,kind,status,place_id)
VALUES('existing-first','Été au musée','Résumé','Description','event','published',1),
      ('existing-second','Été au musée','Résumé','Description','event','published',1),
      ('existing-draft','Brouillon','Résumé','Description','event','draft',1);
""")
statements.append((folder / '012-public-urls.sql').read_text())
statements.append("""
DO $$ BEGIN
 IF (SELECT count(*) FROM idee_city)<>1 THEN RAISE EXCEPTION 'Commune backfill failed'; END IF;
 IF NOT EXISTS(SELECT 1 FROM idee_public_route WHERE path='/bas-rhin/strasbourg/ete-au-musee' AND outing_id=1 AND canonical)
 THEN RAISE EXCEPTION 'Existing outing migration failed'; END IF;
 IF NOT EXISTS(SELECT 1 FROM idee_public_route WHERE path='/bas-rhin/strasbourg/ete-au-musee-2' AND outing_id=2 AND canonical)
 THEN RAISE EXCEPTION 'Collision migration failed'; END IF;
 IF EXISTS(SELECT 1 FROM idee_public_route WHERE outing_id=3 AND canonical)
 THEN RAISE EXCEPTION 'Draft published'; END IF;
 IF (SELECT count(*) FROM idee_public_route WHERE path LIKE '/sorties/%')<>3
 THEN RAISE EXCEPTION 'Legacy aliases missing'; END IF;
END $$;
""")
for filename in ('013-description-jobs.sql','014-description-batches.sql','015-translations.sql'):
    statements.append((folder / filename).read_text())
statements.append("""
INSERT INTO idee_outing_description(outing_id,language,source_description,description_longue,description_courte,model,prompt_version)
SELECT id,'fr','Description','Description française','Résumé français','test',1 FROM idee_outing WHERE status='published';
INSERT INTO idee_outing_translation(outing_id,language,source_hash,title,description_longue,description_courte,model,prompt_version)
SELECT outing_id,'en',source_hash,'Summer at the museum','English description','English summary','test',1 FROM idee_translation_source;
""")
statements.append((folder / '016-translated-urls.sql').read_text())
statements.append("""
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM idee_public_route WHERE path='/en/bas-rhin/strasbourg/summer-at-the-museum' AND outing_id=1 AND canonical)
 THEN RAISE EXCEPTION 'Existing English translation backfill failed'; END IF;
 IF NOT EXISTS(SELECT 1 FROM idee_public_route WHERE path='/en/bas-rhin/strasbourg/summer-at-the-museum-2' AND outing_id=2 AND canonical)
 THEN RAISE EXCEPTION 'Translated collision migration failed'; END IF;
 IF NOT EXISTS(SELECT 1 FROM idee_public_route WHERE path='/en/bas-rhin/strasbourg/ete-au-musee' AND outing_id=1 AND NOT canonical)
 THEN RAISE EXCEPTION 'Old prefixed French alias missing'; END IF;
 IF (SELECT count(*) FROM idee_outing_url WHERE language='en')<>2
 THEN RAISE EXCEPTION 'Unexpected translation publication'; END IF;
END $$;
ROLLBACK;
""")
result = subprocess.run(['psql', '-X', '-v', 'ON_ERROR_STOP=1', '-h', env.get('DB_HOST', 'localhost'),
                         '-p', env.get('DB_PORT', '5432'), '-U', env.get('DB_USER', 'htpweb'),
                         '-d', env.get('DB_NAME', 'idee')], input='\n'.join(statements),
                        text=True, capture_output=True, env=env)
if result.returncode:
    print(result.stderr)
    raise SystemExit(result.returncode)
print('Migration des fiches existantes : communes, URL françaises/traduites, collisions, aliases et brouillons vérifiés ; transaction annulée.')
