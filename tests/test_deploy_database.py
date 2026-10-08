"""Database replacement on a disposable PostgreSQL cluster; never uses project .env.

Run with IDEE_TEST_POSTGRES_BIN=/usr/lib/postgresql/16/bin python3 tests/test_deploy_database.py.
"""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
POSTGRES_BIN = os.environ.get('IDEE_TEST_POSTGRES_BIN')


@unittest.skipUnless(POSTGRES_BIN, 'Set IDEE_TEST_POSTGRES_BIN to run on a disposable PostgreSQL cluster')
class DatabaseReplacementTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='idee-pg-')
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name)
        cls.env = dict(os.environ, PATH=POSTGRES_BIN+os.pathsep+os.environ['PATH'],
                       PGHOST=str(cls.root), PGPORT='55432', PGUSER='deploy_test', PGPASSWORD='')
        cls.run_command(['initdb', '-D', str(cls.root/'cluster'), '-A', 'trust', '-U', 'deploy_test',
                         '--no-locale', '-E', 'UTF8'])
        cls.run_command(['pg_ctl', '-D', str(cls.root/'cluster'), '-l', str(cls.root/'postgres.log'),
                         '-o', "-h '' -k " + shlex.quote(str(cls.root)) + ' -p 55432', '-w', 'start'])
        cls.addClassCleanup(lambda: cls.run_command(['pg_ctl','-D',str(cls.root/'cluster'),'-m','immediate','-w','stop']))

    @classmethod
    def run_command(cls, command, check=True):
        return subprocess.run(command, env=cls.env, capture_output=True, text=True, check=check)

    def setUp(self):
        for name in ('local_test','ovh_test'):
            self.run_command(['dropdb','--if-exists',name])
            self.run_command(['createdb',name])
            self.sql(name, 'CREATE TABLE idee_outing(id integer PRIMARY KEY, title text);')
            (self.root/(name+'.env')).write_text(f'DB_HOST={self.root}\nDB_PORT=55432\nDB_NAME={name}\nDB_USER=deploy_test\nDB_PASSWORD=\n')
        self.sql('local_test', "INSERT INTO idee_outing VALUES(1,'Local');")
        self.sql('ovh_test', "INSERT INTO idee_outing VALUES(2,'OVH'); CREATE TABLE obsolete(id integer);")
        self.dump=self.root/'local.dump'
        self.dump.unlink(missing_ok=True)

    def sql(self, database, query):
        return self.run_command(['psql','-X','-v','ON_ERROR_STOP=1','-d',database,'-tAc',query]).stdout.strip()

    def helper(self, database, action, file=None, check=True):
        return self.run_command(['python3',str(ROOT/'deploy/database.py'),'--config',str(self.root/(database+'.env')),action,
                                 *([str(file)] if file else [])],check=check)

    def test_complete_replacement_removes_obsolete_objects_and_keeps_backup(self):
        backup=self.root/'ovh.dump'
        backup.unlink(missing_ok=True)
        self.helper('ovh_test','backup',backup)
        self.helper('local_test','export',self.dump)
        self.assertEqual(self.dump.stat().st_mode & 0o777,0o600)
        self.helper('ovh_test','restore',self.dump)
        self.assertEqual(self.sql('ovh_test','SELECT title FROM idee_outing'),'Local')
        self.assertEqual(self.sql('ovh_test',"SELECT to_regclass('public.obsolete') IS NULL"),'t')
        self.assertTrue(backup.is_file())

    def test_restore_sql_failure_rolls_back_schema_deletion(self):
        self.helper('local_test','export',self.dump)
        # Let the real pg_restore generate the complete SQL, then inject an
        # error at its end. This proves even an error AFTER loading data rolls
        # back DROP SCHEMA, tables and rows together.
        faulty_bin = self.root/'faulty-bin'
        faulty_bin.mkdir(exist_ok=True)
        wrapper = faulty_bin/'pg_restore'
        wrapper.write_text("#!/usr/bin/python3\nimport subprocess,sys\nfrom pathlib import Path\n"
                           + "result=subprocess.run([" + repr(str(Path(POSTGRES_BIN)/'pg_restore')) + ",*sys.argv[1:]])\n"
                           + "if result.returncode==0 and '--file' in sys.argv:\n"
                           + "    with Path(sys.argv[sys.argv.index('--file')+1]).open('a') as sql: sql.write('\\nSELECT 1/0;\\n')\n"
                           + "sys.exit(result.returncode)\n")
        wrapper.chmod(0o755)
        with patch.dict(self.env, PATH=str(faulty_bin)+os.pathsep+self.env['PATH']):
            result=self.helper('ovh_test','restore',self.dump,check=False)
        self.assertNotEqual(result.returncode,0)
        self.assertEqual(self.sql('ovh_test','SELECT title FROM idee_outing'),'OVH')
        self.assertEqual(self.sql('ovh_test',"SELECT to_regclass('public.obsolete') IS NOT NULL"),'t')

    def test_shared_database_is_refused_without_modification(self):
        self.helper('local_test','export',self.dump)
        self.sql('ovh_test','CREATE SCHEMA other_project;')
        result=self.helper('ovh_test','restore',self.dump,check=False)
        self.assertNotEqual(result.returncode,0)
        self.assertEqual(self.sql('ovh_test','SELECT title FROM idee_outing'),'OVH')


if __name__ == '__main__':
    unittest.main()
