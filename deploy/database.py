#!/usr/bin/env python3
"""Native PostgreSQL checks/backups. No credentials in process arguments or output."""
import os
from pathlib import Path
import subprocess
import sys
from importlib.util import spec_from_file_location, module_from_spec
spec = spec_from_file_location('native_config', Path(__file__).with_name('run-service.py'))
module = module_from_spec(spec)
spec.loader.exec_module(module)

if __name__ == '__main__':
    config = module.settings()
    env = dict(os.environ, PGPASSWORD=config.get('DB_PASSWORD', ''))
    connection = ['-h', config.get('DB_HOST', '127.0.0.1'), '-p', config.get('DB_PORT', '5432'),
                  '-U', config.get('DB_USER', 'idee'), '-d', config.get('DB_NAME', 'idee')]
    if sys.argv[1:] == ['check']:
        subprocess.run(['psql', '-X', '-v', 'ON_ERROR_STOP=1', *connection, '-tAc', 'SELECT 1'],
                       env=env, check=True, stdout=subprocess.DEVNULL)
    elif len(sys.argv) == 3 and sys.argv[1] == 'backup':
        path = Path(sys.argv[2])
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'wb') as output:
            subprocess.run(['pg_dump', *connection, '-Fc'], env=env, check=True, stdout=output)
        subprocess.run(['pg_restore', '--list', str(path)], check=True, stdout=subprocess.DEVNULL)
    else:
        raise SystemExit('Usage : database.py check | backup fichier.dump')
