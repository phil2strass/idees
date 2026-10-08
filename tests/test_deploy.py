"""Native deployment safety checks. Commands are simulated; no remote or production writes."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import secrets
import subprocess
import tarfile
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
MANAGED = ('idee-front', 'idee-service', 'idee-mcp', 'scripts', 'tests', 'docs', 'deploy')
FILES = ('.gitignore', '.env.example', 'README.md', 'AGENTS.md', 'CODEX.md', 'requirements.txt',
         'start-front.sh', 'deploy.sh', 'download-images.sh', 'import-outings.sh', 'generate-all-translations.sh')

class DeployTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='idee-native-tests-')
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.source, self.live = self.root / 'source', self.root / 'live'
        self.stage = Path('/tmp/idee-deploy.' + secrets.token_hex(4))
        self.stage.mkdir()
        self.addCleanup(shutil.rmtree, self.stage, True)
        for root, marker in ((self.source, 'new'), (self.live, 'old')):
            root.mkdir()
            for directory in MANAGED:
                (root / directory).mkdir()
            for name in FILES:
                shutil.copy2(ROOT / name, root / name)
            for file in (ROOT / 'deploy').iterdir():
                if file.is_file() and file.suffix in ('.sh', '.py'):
                    shutil.copy2(file, root / 'deploy' / file.name)
            (root / 'idee-front/version.txt').write_text(marker)
            (root / 'scripts/expand_calendar_json.py').write_text('print("[]")\n')
            (root / 'scripts/project_calendar.py').write_text('# fixture\n')
        (self.live / 'deploy/.env').write_text('DB_PASSWORD=PRIVATE_PRODUCTION_SECRET\nDB_USER=idee\n')
        (self.source / 'deploy/.env').write_text('DO_NOT_UPLOAD=local-secret\n')
        (self.source / 'idee-front/.env.production').write_text('DO_NOT_UPLOAD=local-secret\n')
        (self.live / 'data/images').mkdir(parents=True)
        (self.live / 'data/images/photo.jpg').write_bytes(b'keep-image')
        (self.live / 'logs').mkdir()
        (self.live / '.runtime/releases/old').mkdir(parents=True)
        (self.live / '.runtime/releases/old/service.jar').write_bytes(b'old-jar')
        (self.live / '.runtime/current').symlink_to(self.live / '.runtime/releases/old')
        (self.live / '.venv/bin').mkdir(parents=True)
        (self.live / '.venv/bin/python').symlink_to(shutil.which('python3'))
        (self.live / 'idee-mcp/.venv').mkdir()
        (self.live / 'idee-mcp/.venv/runtime').write_text('keep')
        (self.live / '.tunnel').mkdir()
        (self.live / '.tunnel/key').write_text('keep-private')
        (self.live / 'idee-front/obsolete.ts').write_text('obsolete')
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.log = self.root / 'calls.log'
        fake = self.bin / 'fake-command'
        fake.write_text('''#!/usr/bin/env python3
import json,os,pathlib,sys
name=pathlib.Path(sys.argv[0]).name
args=sys.argv[1:]
with open(os.environ['DEPLOY_TEST_LOG'],'a') as log: log.write(json.dumps([name,*args])+'\\n')
failure=os.environ.get('DEPLOY_TEST_FAIL','')
if name=='mvn':
    if failure=='build': sys.exit(1)
    dest=pathlib.Path('idee-service/target');dest.mkdir(parents=True,exist_ok=True)
    (dest/'idee-service-0.0.1-SNAPSHOT.jar').write_bytes(b'new-jar')
if name=='npm' and args==['run','build']:
    dest=pathlib.Path('dist/idee/server');dest.mkdir(parents=True,exist_ok=True)
    (dest/'server.mjs').write_text('// simulated SSR')
    browser=pathlib.Path('dist/idee/browser/assets');browser.mkdir(parents=True,exist_ok=True)
    (browser/'deploy-version.json').write_text(pathlib.Path('src/assets/deploy-version.json').read_text())
if name=='psql' and failure=='database': sys.exit(1)
if name=='pg_dump':
    if failure=='backup': sys.exit(1)
    sys.stdout.buffer.write(b'SIMULATED_BACKUP')
if name=='sudo' and 'restart' in args and '-l' not in args and failure=='restart': sys.exit(1)
if name=='curl' and '--output' in args:
    target=args[args.index('--output')+1]
    if target!='/dev/null':
        p=pathlib.Path(target)
        if target.endswith('.html'):
            p.write_text('<idee-root></idee-root>' if failure=='ssr' else '<idee-root ng-server-context="ssr">Rendered</idee-root>')
        else: p.write_text(json.dumps({'version':('b' if failure=='version' else 'a')*64}))
''')
        fake.chmod(0o755)
        for name in ('java', 'mvn', 'node', 'npm', 'psql', 'pg_dump', 'pg_restore', 'systemctl', 'sudo', 'curl'):
            (self.bin / name).symlink_to(fake)
        result = subprocess.run(['bash', str(self.source / 'deploy/package.sh'), str(self.stage / 'source.tar.gz')], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)

    def update(self, failure='', extra_args=()):
        env = dict(os.environ, PATH=f'{self.bin}:{os.environ["PATH"]}',
                   DEPLOY_TEST_LOG=str(self.log), DEPLOY_TEST_FAIL=failure)
        return subprocess.run(['bash', str(ROOT / 'deploy/remote-update.sh'), str(self.live), str(self.stage),
                               'false', 'a'*64, *extra_args], env=env, stdin=subprocess.DEVNULL,
                              capture_output=True, text=True)

    def calls(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def test_archive_excludes_secrets_and_runtime(self):
        with tarfile.open(self.stage / 'source.tar.gz') as archive:
            names = archive.getnames()
        self.assertNotIn('deploy/.env', names)
        self.assertNotIn('idee-front/.env.production', names)
        self.assertIn('AGENTS.md', names)
        self.assertIn('deploy/run-service.py', names)
        self.assertFalse(any(name.startswith(('data/', '.runtime/')) for name in names))

    def test_actual_project_packages_without_network(self):
        result = subprocess.run(['bash', str(ROOT / 'deploy.sh'), '--check'], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('Vérification locale réussie', result.stdout)

    def test_success_preserves_data_and_switches_an_immutable_release(self):
        result = self.update()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.live / 'idee-front/version.txt').read_text(), 'new')
        self.assertFalse((self.live / 'idee-front/obsolete.ts').exists())
        self.assertEqual((self.live / 'data/images/photo.jpg').read_bytes(), b'keep-image')
        self.assertIn('PRIVATE_PRODUCTION_SECRET', (self.live / 'deploy/.env').read_text())
        self.assertTrue((self.live / 'idee-mcp/.venv/runtime').exists())
        self.assertTrue((self.live / '.tunnel/key').exists())
        self.assertFalse((self.live / 'idee-front/.env.production').exists())
        self.assertEqual((self.live / '.runtime/current/service.jar').read_bytes(), b'new-jar')
        self.assertEqual((self.live / '.runtime/releases/old/service.jar').read_bytes(), b'old-jar')
        backups = list((self.live / 'backups').glob('*/database.dump'))
        self.assertEqual(len(backups), 1)
        self.assertTrue(backups[0].with_name('images.tar.gz').is_file())
        calls = self.calls()
        dump = next(i for i,c in enumerate(calls) if c[0]=='pg_dump')
        restart = next(i for i,c in enumerate(calls) if c[:3]==['sudo','-n','/usr/bin/systemctl'] and 'restart' in c)
        self.assertLess(dump, restart)
        self.assertNotIn('PRIVATE_PRODUCTION_SECRET', result.stdout+result.stderr+self.log.read_text())
        self.assertFalse(any('createdb' in call or '--clean' in call for call in calls))

    def test_build_backup_and_database_failures_leave_current_release_and_sources(self):
        for failure in ('build', 'backup', 'database'):
            with self.subTest(failure=failure):
                if not self.stage.exists():
                    self.stage.mkdir()
                    subprocess.run(['bash', str(self.source / 'deploy/package.sh'), str(self.stage/'source.tar.gz')], check=True, capture_output=True)
                result = self.update(failure)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual((self.live/'idee-front/version.txt').read_text(), 'old')
                self.assertEqual((self.live/'.runtime/current/service.jar').read_bytes(), b'old-jar')
                self.assertFalse(any(call[:3]==['sudo','-n','/usr/bin/systemctl'] and 'restart' in call for call in self.calls()))

    def test_ssr_and_wrong_version_are_not_success(self):
        result = self.update('version')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Version locale inattendue', result.stderr)
        self.assertTrue(list((self.live/'backups').glob('*/database.dump')))

    def test_static_shell_is_rejected_even_with_a_healthy_http_response(self):
        result = self.update('ssr')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('rendu serveur attendu', result.stderr)

    def test_public_wrong_version_is_rejected_after_successful_ssh(self):
        ssh = self.bin/'ssh'
        ssh.write_text('#!/usr/bin/env python3\nimport sys\nif "mktemp" in sys.argv[-1]: print(' + repr(str(self.stage)) + ')\n')
        ssh.chmod(0o755)
        scp = self.bin/'scp'
        scp.write_text('#!/usr/bin/env bash\nexit 0\n'); scp.chmod(0o755)
        env = dict(os.environ, PATH=f'{self.bin}:{os.environ["PATH"]}',
                   DEPLOY_TEST_LOG=str(self.log), DEPLOY_TEST_FAIL='version',
                   IDEE_DEPLOY_HOST='mock-host', IDEE_DEPLOY_DIR=str(self.live))
        result = subprocess.run(['bash', str(ROOT/'deploy.sh')], env=env, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('site public ne sert pas la version attendue', result.stderr)

    def test_database_dump_argument_and_legacy_options_are_rejected(self):
        result = self.update(extra_args=('/tmp/database.dump',))
        self.assertEqual(result.returncode, 2)
        self.assertFalse(self.log.exists())
        for option in ('--replace-db', '--export-db'):
            result = subprocess.run(['bash', str(ROOT/'deploy.sh'), option], capture_output=True, text=True)
            self.assertEqual(result.returncode, 2)

    def test_systemd_units_render_without_installing_or_starting(self):
        dest = self.root/'units'
        result = subprocess.run(['python3', str(ROOT/'deploy/install-services.py'), '--output-dir', str(dest), '--user', 'debian'], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('User=debian', (dest/'idee-api.service').read_text())
        self.assertIn('04:00:00 UTC', (dest/'idee-import.timer').read_text())
        self.assertIn('Type=oneshot', (dest/'idee-import.service').read_text())

    def test_native_launcher_uses_fixed_release_and_keeps_secrets_out_of_node(self):
        spec = importlib.util.spec_from_file_location('native_launcher', ROOT/'deploy/run-service.py')
        module = importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        api, env = module.command('api', self.live)
        self.assertEqual(api[-1], str(self.live/'.runtime/releases/old/service.jar'))
        self.assertEqual(env['IDEE_IMAGES_DIRECTORY'], str(self.live/'data/images'))
        self.assertEqual(env['SERVER_ADDRESS'], '127.0.0.1')
        node, env = module.command('ssr', self.live)
        self.assertEqual(node[0], 'node')
        self.assertNotIn('DB_PASSWORD', env)
        self.assertEqual(env['HOST'], '127.0.0.1')

if __name__ == '__main__':
    unittest.main()
