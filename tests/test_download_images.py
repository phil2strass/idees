"""Exercise the shell launcher against a local simulated API, without project secrets."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'download-images.sh'
TOKEN = 'test-image-download-token-1234567890'

class DownloadImagesTest(unittest.TestCase):
    def launch(self, statuses, args=(), enabled=True):
        calls = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass
            def reply(self, value, code=200):
                self.send_response(code)
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                self.wfile.write(json.dumps(value).encode())
            def do_POST(self):
                calls.append(('POST', self.path, self.headers.get('Authorization')))
                self.reply({'queued': 2}, 202) if enabled else self.reply({'error': 'disabled'}, 503)
            def do_GET(self):
                calls.append(('GET', self.path, self.headers.get('Authorization')))
                states = statuses.pop(0) if len(statuses) > 1 else statuses[0]
                self.reply({'enabled': enabled, 'counts': [{'state': k, 'count': v} for k, v in states.items()],
                            'recentErrors': []})
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                target = Path(directory) / SCRIPT.name
                shutil.copy2(SCRIPT, target)
                (Path(directory) / '.env').write_text('IDEE_IMPORT_TOKEN=' + TOKEN + '\n')
                env = dict(os.environ, IDEE_API_BASE_URL=f'http://127.0.0.1:{server.server_port}', IDEE_IMAGES_POLL_SECONDS='0')
                result = subprocess.run(['bash', str(target), *args], env=env, capture_output=True, text=True, timeout=15)
        finally:
            server.shutdown()
            server.server_close()
        self.assertNotIn(TOKEN, result.stdout + result.stderr)
        self.assertTrue(all(call[2] == 'Bearer ' + TOKEN for call in calls))
        return result, calls

    def test_launch_follows_progress_until_completed(self):
        result, calls = self.launch([{'pending': 2}, {'ready': 2}])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('Toutes les images en file sont téléchargées.', result.stdout)
        self.assertEqual([(x[0], x[1]) for x in calls], [
            ('POST', '/api/admin/images/download-all-missing'),
            ('GET', '/api/admin/images/status'), ('GET', '/api/admin/images/status')])

    def test_status_does_not_launch_downloads(self):
        result, calls = self.launch([{'pending': 2}], ['--status'])
        self.assertEqual(result.returncode, 0)
        self.assertEqual([x[0] for x in calls], ['GET'])

    def test_watch_follows_progress_without_relaunching(self):
        result, calls = self.launch([{'ready': 1, 'pending': 1}, {'ready': 2}], ['--watch'])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([x[0] for x in calls], ['GET', 'GET'])
        self.assertIn('1/2 (50.0 %)', result.stdout)
        self.assertIn('2/2 (100.0 %)', result.stdout)

    def test_failed_downloads_do_not_claim_success_or_wait_forever(self):
        result, _ = self.launch([{'ready': 1, 'retrying': 1}])
        self.assertEqual(result.returncode, 2)
        self.assertIn('Passage terminé avec des erreurs.', result.stdout)

    def test_no_wait_and_disabled_api(self):
        result, calls = self.launch([{}], ['--no-wait'])
        self.assertEqual(result.returncode, 0)
        self.assertEqual([x[0] for x in calls], ['POST'])
        result, calls = self.launch([{}], enabled=False)
        self.assertEqual(result.returncode, 1)
        self.assertEqual([x[0] for x in calls], ['POST'])
        self.assertIn('redémarrée', result.stderr)

    def test_missing_key_has_clear_error(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / SCRIPT.name
            shutil.copy2(SCRIPT, target)
            env = dict(os.environ)
            env.pop('IDEE_IMPORT_TOKEN', None)
            result = subprocess.run(['bash', str(target)], env=env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn('Renseignez IDEE_IMPORT_TOKEN', result.stderr)

if __name__ == '__main__':
    unittest.main()
