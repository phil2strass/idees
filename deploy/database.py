#!/usr/bin/env python3
"""Native PostgreSQL backup/replacement. Credentials never enter command arguments."""
import argparse
import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def settings(path):
    if not path.is_file():
        raise SystemExit('Fichier de configuration PostgreSQL absent.')
    values = {}
    for line in path.read_text().splitlines():
        if '=' in line and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            if key.strip().startswith('DB_'):
                values[key.strip()] = ' '.join(shlex.split(value, comments=True))
    if not values.get('DB_NAME') or not values.get('DB_USER'):
        raise SystemExit('DB_NAME et DB_USER sont requis dans la configuration.')
    return values


def connection(config):
    return ['-h', config.get('DB_HOST', '127.0.0.1'), '-p', config.get('DB_PORT', '5432'),
            '-U', config['DB_USER'], '-d', config['DB_NAME']]


def check_replace(config, env):
    # This application owns public in a dedicated database. Never cascade into
    # another application's schema or silently leave other schemas untouched.
    query = """SELECT CASE WHEN
      NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname <> 'public'
                  AND nspname <> 'information_schema' AND nspname NOT LIKE 'pg_%')
      AND to_regclass('public.idee_outing') IS NOT NULL
      AND EXISTS (SELECT 1 FROM pg_namespace WHERE nspname='public'
                  AND pg_has_role(nspowner, 'USAGE'))
      AND has_database_privilege(current_database(), 'CREATE')
      THEN 'ok' ELSE 'refused' END"""
    result = subprocess.run(['psql', '-X', '-v', 'ON_ERROR_STOP=1', *connection(config), '-tAc', query],
                            env=env, check=True, capture_output=True, text=True)
    if result.stdout.strip() != 'ok':
        raise SystemExit('Remplacement refusé : base dédiée avec idee_outing dans public, propriétaire du schéma et droit CREATE requis ; aucun autre schéma utilisateur autorisé.')


def backup(config, env, path, public_only=False):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, 'wb') as output:
            extra = ['--schema=public', '--no-owner', '--no-privileges'] if public_only else []
            subprocess.run(['pg_dump', *connection(config), '-Fc', *extra], env=env, check=True, stdout=output)
        subprocess.run(['pg_restore', '--list', str(path)], env=env, check=True, stdout=subprocess.DEVNULL)
    except BaseException:
        path.unlink(missing_ok=True)
        raise


def restore(config, env, path):
    check_replace(config, env)
    listing = subprocess.run(['pg_restore', '--list', str(path)], env=env, check=True, capture_output=True, text=True).stdout
    # public is explicitly recreated below. Remove only its SCHEMA entry;
    # all contained tables, sequences, indexes, constraints and data are kept.
    public_schema = re.compile(r'^\d+; \d+ \d+ SCHEMA - public \S+\s*$')
    if not any(public_schema.match(line) for line in listing.splitlines()):
        raise SystemExit('Archive refusée : schéma public absent.')
    with tempfile.TemporaryDirectory(prefix='idee-restore-', dir=path.parent) as directory:
        directory = Path(directory)
        toc = directory / 'toc.list'
        toc.write_text('\n'.join(line for line in listing.splitlines() if not public_schema.match(line)) + '\n')
        sql = directory / 'database.sql'
        subprocess.run(['pg_restore', '--no-owner', '--no-privileges', '--exit-on-error',
                        '--use-list', str(toc), '--file', str(sql), str(path)], env=env, check=True)
        reset = directory / 'reset.sql'
        reset.write_text('DROP SCHEMA public CASCADE;\nCREATE SCHEMA public;\n')
        # Both files execute in ONE transaction: restore failure rolls back the
        # schema deletion too. Owners/roles/secrets remain those of the target.
        subprocess.run(['psql', '-X', '--single-transaction', '-v', 'ON_ERROR_STOP=1',
                        *connection(config), '-f', str(reset), '-f', str(sql)],
                       env=env, check=True, stdout=subprocess.DEVNULL)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, default=ROOT / 'deploy/.env')
    parser.add_argument('action', choices=['check', 'check-replace', 'backup', 'export', 'restore'])
    parser.add_argument('file', nargs='?', type=Path)
    args = parser.parse_args()
    if (args.action in ('backup', 'export', 'restore')) != (args.file is not None):
        parser.error('Un fichier est requis uniquement pour backup, export et restore.')
    config = settings(args.config)
    config_root = args.config.resolve().parent
    if config_root.name == 'deploy':
        config_root = config_root.parent
    native_path = str(config_root / '.tools/bin') + os.pathsep + os.environ.get('PATH', '/usr/bin:/bin')
    env = dict(os.environ, PATH=native_path, PGPASSWORD=config.get('DB_PASSWORD', ''), PGCONNECT_TIMEOUT='15')
    if args.action == 'check':
        subprocess.run(['psql', '-X', '-v', 'ON_ERROR_STOP=1', *connection(config), '-tAc', 'SELECT 1'],
                       env=env, check=True, stdout=subprocess.DEVNULL)
    elif args.action == 'check-replace':
        check_replace(config, env)
    elif args.action == 'export':
        check_replace(config, env)
        backup(config, env, args.file, public_only=True)
    elif args.action == 'backup':
        backup(config, env, args.file)
    else:
        restore(config, env, args.file)


if __name__ == '__main__':
    main()
