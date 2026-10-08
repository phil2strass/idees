"""Deployment safety checks. Docker/HTTP are mocked; no SSH or production writes."""
import os
import json
from pathlib import Path
import shutil
import secrets
import subprocess
import tarfile
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
MANAGED = ('idee-front', 'idee-service', 'idee-mcp', 'scripts', 'tests', 'docs', 'deploy')
FILES = ('compose.yaml', '.dockerignore', '.gitignore', '.env.example', 'README.md',
         'requirements.txt', 'start-front.sh', 'deploy.sh')

class DeployTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='idee-deploy-tests-')
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.source = self.root / 'source'
        self.live = self.root / 'live'
        for root, marker in ((self.source, 'new'), (self.live, 'old')):
            root.mkdir()
            for directory in MANAGED:
                (root / directory).mkdir()
            for name in FILES:
                (root / name).write_text(marker)
            for name in ('package.sh', 'remote-update.sh'):
                shutil.copy2(ROOT / 'deploy' / name, root / 'deploy' / name)
            (root / 'idee-front' / 'version.txt').write_text(marker)
        (self.live / 'deploy/.env').write_text('PRIVATE_PRODUCTION_SECRET=keep-this\n')
        (self.live / 'idee-front/obsolete.ts').write_text('removed upstream')
        (self.live / 'idee-mcp/.venv').mkdir()
        (self.live / 'idee-mcp/.venv/runtime').write_text('keep runtime')
        (self.live / '.tunnel').mkdir()
        (self.live / '.tunnel/key').write_text('keep tunnel')
        (self.live / 'server-notes.txt').write_text('keep unmanaged root file')
        (self.source / 'deploy/.env').write_text('LOCAL_SECRET_MUST_NOT_TRANSFER')
        (self.source / 'idee-front/.env.production').write_text('LOCAL_SECRET_MUST_NOT_TRANSFER')
        self.stage = Path('/tmp') / ('idee-deploy.' + secrets.token_hex(4))
        self.stage.mkdir(mode=0o700)
        self.addCleanup(lambda: shutil.rmtree(self.stage, ignore_errors=True))
        result = subprocess.run(['bash', str(self.source / 'deploy/package.sh'), str(self.stage / 'source.tar.gz')], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.log = self.root / 'docker.log'
        (self.bin / 'docker').write_text('''#!/usr/bin/env python3
import os, sys, json
args = sys.argv[1:]
with open(os.environ['DEPLOY_TEST_LOG'], 'a') as log:
    log.write(json.dumps(args) + '\\n')
mode = os.environ.get('DEPLOY_TEST_FAIL', '')
if 'info' in args or 'version' in args: pass
elif '--help' in args: print('--wait-timeout')
elif 'build' in args:
    from pathlib import Path
    source = Path(args[args.index('--project-directory') + 1])
    assert (source / 'idee-front/version.txt').stat().st_mode & 0o044, 'Archive permissions were restricted by umask'
    assert (source / 'idee-front/src/assets/deploy-version.json').stat().st_mode & 0o044, 'Version marker must be publicly readable'
    if mode == 'build': sys.exit(1)
elif 'run' in args:
    if mode == 'runtime': sys.exit(1)
    print('[]')
elif 'pg_dump' in args:
    if mode == 'dump': sys.exit(1)
    print('MOCK_DATABASE_DUMP')
elif 'pg_restore' in args:
    assert 'MOCK_DATABASE_DUMP' in sys.stdin.read()
elif 'images' in args: print('old-image-id')
elif 'port' in args: print('127.0.0.1:9081')
''')
        (self.bin / 'curl').write_text("""#!/usr/bin/env python3
import sys, json, os
args = sys.argv[1:]
if '--output' in args:
    target = args[args.index('--output') + 1]
    if target != '/dev/null':
        with open(target, 'w') as output:
            if target.endswith('.html'):
                output.write('<idee-root></idee-root>' if os.environ.get('DEPLOY_TEST_FAIL') == 'ssr' else '<idee-root ng-server-context="ssr">Rendered</idee-root>')
            else:
                json.dump({'version': ('b' if os.environ.get('DEPLOY_TEST_FAIL') == 'version' else 'a') * 64}, output)
""")
        for file in self.bin.iterdir():
            file.chmod(0o755)

    def update(self, failure='', extra_args=()):
        env = dict(os.environ, PATH=f'{self.bin}:{os.environ["PATH"]}',
                   DEPLOY_TEST_LOG=str(self.log), DEPLOY_TEST_FAIL=failure)
        args = ['bash', str(ROOT / 'deploy/remote-update.sh'), str(self.live), str(self.stage), 'false', 'a' * 64]
        args.extend(extra_args)
        return subprocess.run(args, stdin=subprocess.DEVNULL, env=env, capture_output=True, text=True)

    def test_archive_excludes_credentials(self):
        with tarfile.open(self.stage / 'source.tar.gz') as archive:
            names = archive.getnames()
            self.assertNotIn('deploy/.env', names)
            self.assertNotIn('idee-front/.env.production', names)
            self.assertIn('.env.example', names)
            self.assertIn('deploy.sh', names)
            self.assertIn('start-front.sh', names)

    def test_actual_project_can_be_packaged_without_network_access(self):
        result = subprocess.run(['bash', str(ROOT / 'deploy.sh'), '--check'],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('Vérification locale réussie', result.stdout)

    def test_removed_database_options_are_rejected(self):
        for option in ('--export-db', '--replace-db'):
            with self.subTest(option=option):
                result = subprocess.run(['bash', str(ROOT / 'deploy.sh'), option, '--check'],
                                        capture_output=True, text=True)
                self.assertEqual(result.returncode, 2, result.stderr)
                self.assertIn('Option inconnue', result.stderr)
                self.assertNotIn('Archive de sources créée', result.stdout)

    def test_remote_update_rejects_a_database_dump_argument(self):
        result = self.update(extra_args=('/tmp/old-database.dump',))
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn('Aucun import de base accepte', result.stderr)
        self.assertFalse(self.log.exists())
        self.assertEqual((self.live / 'idee-front/version.txt').read_text(), 'old')

    def test_success_preserves_state_and_removes_obsolete_sources(self):
        result = self.update()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.live / 'idee-front/version.txt').read_text(), 'new')
        self.assertFalse((self.live / 'idee-front/obsolete.ts').exists())
        self.assertIn('PRIVATE_PRODUCTION_SECRET', (self.live / 'deploy/.env').read_text())
        self.assertTrue((self.live / 'idee-mcp/.venv/runtime').exists())
        self.assertTrue((self.live / '.tunnel/key').exists())
        self.assertTrue((self.live / 'server-notes.txt').exists())
        self.assertFalse((self.live / 'idee-front/.env.production').exists())
        self.assertEqual(len(list((self.live / 'backups').glob('*/database.dump'))), 1)
        log = self.log.read_text()
        self.assertLess(log.index('"pg_dump"'), log.index('"--force-recreate"'))
        self.assertIn('"--no-deps"', log)
        self.assertNotIn('PRIVATE_PRODUCTION_SECRET', result.stdout + result.stderr)
        calls = [json.loads(line) for line in log.splitlines()]
        build = next(args for args in calls if 'build' in args)
        restart = next(args for args in calls if '--force-recreate' in args)
        self.assertEqual(build[-3:], ['api', 'ssr', 'web'])
        self.assertEqual(restart[-3:], ['api', 'ssr', 'web'])
        self.assertIn(['image', 'tag', 'idee-api', 'idee-datatourisme:cron'], calls)
        for args in calls:
            self.assertNotIn('createdb', args)
            self.assertNotIn('psql', args)
            self.assertNotIn('stop', args)
            if 'pg_restore' in args:
                self.assertEqual(args[args.index('pg_restore') + 1:], ['--list'])

    def test_ssh_success_without_deployment_is_not_public_success(self):
        shutil.copy2(ROOT / 'deploy.sh', self.source / 'deploy.sh')
        for directory in ('idee-front', 'idee-service'):
            (self.source / directory / 'Dockerfile').write_text('FROM scratch\n')
        (self.bin / 'ssh').write_text('#!/usr/bin/env python3\nimport sys\nif "mktemp" in sys.argv[-1]: print(' + repr(str(self.stage)) + ')\n')
        (self.bin / 'scp').write_text('#!/usr/bin/env bash\nexit 0\n')
        (self.bin / 'ssh').chmod(0o755)
        (self.bin / 'scp').chmod(0o755)
        env = dict(os.environ, PATH=f'{self.bin}:{os.environ["PATH"]}',
                   IDEE_DEPLOY_HOST='mock-host', IDEE_DEPLOY_DIR=str(self.live),
                   IDEE_DEPLOY_URL='https://mock.invalid')
        result = subprocess.run(['bash', str(self.source / 'deploy.sh')], env=env, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('ne sert pas la version attendue', result.stderr)
        self.assertNotIn('Déploiement terminé', result.stdout)

    def test_wrong_served_version_is_failure(self):
        result = self.update('version')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('ne sert pas la nouvelle version', result.stderr)

    def test_static_shell_is_not_successful_ssr_deployment(self):
        result = self.update('ssr')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('ne fournit pas le rendu serveur attendu', result.stderr)

    def test_runtime_failure_prevents_switch(self):
        result = self.update('runtime')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('moteur de calendrier', result.stderr)
        self.assertEqual((self.live / 'idee-front/version.txt').read_text(), 'old')
        self.assertNotIn('"--force-recreate"', self.log.read_text())

    def test_build_failure_keeps_live_sources(self):
        result = self.update('build')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('construction des images', result.stderr)
        self.assertEqual((self.live / 'idee-front/version.txt').read_text(), 'old')
        self.assertNotIn('"--force-recreate"', self.log.read_text())
        self.assertFalse((self.live / 'backups').exists())

    def test_dump_failure_prevents_switch(self):
        result = self.update('dump')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('sauvegarde des sources', result.stderr)
        self.assertEqual((self.live / 'idee-front/version.txt').read_text(), 'old')
        self.assertNotIn('"--force-recreate"', self.log.read_text())
        self.assertEqual(len(list((self.live / 'backups').glob('*/sources.tar.gz'))), 1)

if __name__ == '__main__':
    unittest.main()
