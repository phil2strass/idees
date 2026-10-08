#!/usr/bin/env python3
"""Launch a native service from an immutable release without exposing credentials."""
import os
from pathlib import Path
import shlex
import sys

ROOT = Path(__file__).resolve().parents[1]


def settings(root=ROOT):
    values = {}
    for path in (root / 'deploy/.env',):
        if not path.is_file():
            raise SystemExit('Configuration deploy/.env absente.')
        for line in path.read_text().splitlines():
            if '=' in line and not line.lstrip().startswith('#'):
                key, value = line.split('=', 1)
                values[key.strip()] = ' '.join(shlex.split(value, comments=True))
    return values


def command(mode, root=ROOT):
    config = settings(root)
    native_path = str(root / ".tools/bin") + os.pathsep + os.environ.get("PATH", "/usr/local/bin:/usr/bin:/bin")
    release = (root / '.runtime/current').resolve(strict=True)
    python = str(root / '.venv/bin/python')
    base = dict(os.environ, **config)
    base.update(PATH=native_path, SERVER_ADDRESS='127.0.0.1', PORT='8087', DB_CONTEXTS='production',
                IDEE_IMAGES_DIRECTORY=str(root / 'data/images'),
                IDEE_CALENDAR_PYTHON=python,
                IDEE_CALENDAR_SCRIPT=str(release / 'scripts/expand_calendar_json.py'))
    if mode == 'ssr':
        # Node has no need to receive database or provider credentials.
        env = {key: value for key, value in os.environ.items() if key in ('PATH', 'LANG', 'LC_ALL')}
        env.update(PATH=native_path, HOST='127.0.0.1', PORT='4000', API_ORIGIN='http://127.0.0.1:8087',
                   PUBLIC_ORIGIN=config.get('IDEE_PUBLIC_ORIGIN', 'https://ideesdesorties.eu'))
        return ['node', '--max-old-space-size=384', str(release / 'frontend/server/server.mjs')], env
    if mode == 'import':
        private = root / 'deploy/.env.datatourisme'
        if private.is_file():
            for line in private.read_text().splitlines():
                if line.startswith('DATATOURISME_API_KEY='):
                    base['DATATOURISME_API_KEY'] = ' '.join(shlex.split(line.split('=', 1)[1]))
        base.update(DATATOURISME_ENABLED='true', DATATOURISME_BATCH='true',
                    IDEE_IMAGES_ENABLED='false', MISTRAL_AUTO_ENABLED='true',
                    OPENAI_TRANSLATIONS_ENABLED='true')
    elif mode == 'api':
        # Paid background processing belongs to the daily import process only.
        base.update(MISTRAL_AUTO_ENABLED='false', OPENAI_TRANSLATIONS_ENABLED='false')
    else:
        raise SystemExit('Mode attendu : api, ssr ou import.')
    return ['java', '-Xms64m', '-Xmx512m', '-jar', str(release / 'service.jar')], base


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('Usage : run-service.py api|ssr|import')
    args, env = command(sys.argv[1])
    os.chdir(ROOT)
    os.execvpe(args[0], args, env)
