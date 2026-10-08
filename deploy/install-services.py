#!/usr/bin/env python3
"""Install scoped systemd units; never start/restart application services implicitly."""
import argparse
import getpass
import os
from pathlib import Path
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--user', default=getpass.getuser())
parser.add_argument('--output-dir', type=Path, help='Render only; do not install or call systemctl')
args = parser.parse_args()
if not re.fullmatch(r'[a-zA-Z0-9_][a-zA-Z0-9_-]*', args.user) or not re.fullmatch(r'/[a-zA-Z0-9_./-]+', str(root)):
    raise SystemExit('Utilisateur ou chemin de projet invalide.')
units = {}
for name, mode, title in [('idee-api', 'api', 'API'), ('idee-ssr', 'ssr', 'Angular SSR'), ('idee-import', 'import', 'Import quotidien')]:
    oneshot = mode == 'import'
    units[name + '.service'] = f'''[Unit]
Description=Idees Alsace - {title}
Wants=network-online.target
After=network-online.target
ConditionPathExists={root}/.runtime/current/service.jar

[Service]
Type={'oneshot' if oneshot else 'simple'}
User={args.user}
WorkingDirectory={root}
ExecStart=/usr/bin/python3 {root}/deploy/run-service.py {mode}
{('TimeoutStartSec=4h' if oneshot else 'Restart=on-failure' + chr(10) + 'RestartSec=5')}
TimeoutStopSec=60
MemoryMax={'512M' if mode == 'ssr' else '768M'}
UMask=0027
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=read-only
ReadWritePaths={root}/data {root}/logs
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6
CapabilityBoundingSet=

[Install]
WantedBy=multi-user.target
'''
units['idee-import.timer'] = '''[Unit]
Description=Import quotidien Idees Alsace a 04:00 UTC

[Timer]
OnCalendar=*-*-* 04:00:00 UTC
Persistent=true
Unit=idee-import.service

[Install]
WantedBy=timers.target
'''
if args.output_dir:
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for name, text in units.items():
        (args.output_dir / name).write_text(text)
else:
    if os.geteuid() == 0:
        raise SystemExit('Lancer comme utilisateur applicatif, avec sudo disponible, pas comme root.')
    (root / 'data/images').mkdir(parents=True, exist_ok=True)
    (root / 'logs').mkdir(exist_ok=True, mode=0o750)
    with tempfile.TemporaryDirectory(prefix='idee-units-') as directory:
        for name, text in units.items():
            source = Path(directory) / name
            source.write_text(text)
            subprocess.run(['sudo', '-n', 'install', '-o', 'root', '-g', 'root', '-m', '0644',
                            str(source), '/etc/systemd/system/' + name], check=True)
    subprocess.run(['sudo', '-n', 'systemctl', 'daemon-reload'], check=True)
    print('Unités installées ; aucun service démarré ou redémarré.')
