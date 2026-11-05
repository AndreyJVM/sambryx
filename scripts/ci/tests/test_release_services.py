"""Check external service responses and publication policy."""

import json
from pathlib import Path
import tempfile
import unittest
import urllib.request

from release_fixture import DIGEST, SHA, release


class ExternalBoundaryTest(unittest.TestCase):
    def setUp(self):
        from unittest.mock import patch
        self.patch = patch
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.io = release.Services('owner/repo')

    def test_api_pagination_accepts_each_complete_json_page_without_slurp(self):
        from unittest.mock import patch
        with patch.object(self.io, 'command', return_value=b'[{"id":1}]\n[{"id":2}]\n') as command:
            self.assertEqual(self.io.api('releases?per_page=1', pages=True), [[{'id': 1}], [{'id': 2}]])
            self.assertNotIn('--slurp', command.call_args.args)

    def response(self, data):
        import io
        return io.BytesIO(data)

    def test_registry_absence_requires_structured_manifest_unknown(self):
        import urllib.error
        for status, code in ((401, 'UNAUTHORIZED'), (429, 'TOOMANYREQUESTS'),
                             (500, 'UNKNOWN'), (404, 'UNKNOWN')):
            error = urllib.error.HTTPError('https://registry', status, '', {},
                                          self.response(json.dumps({'errors': [{'code': code}]}).encode()))
            with self.subTest(status=status), self.patch.object(urllib.request, 'urlopen', side_effect=error):
                self.io.registry_token = 'fixture'
                with self.assertRaises(ValueError):
                    self.io.digest('1.2.3')
        error = urllib.error.HTTPError('https://registry', 404, '', {}, self.response(
            b'{"errors":[{"code":"MANIFEST_UNKNOWN"}]}'))
        with self.patch.object(urllib.request, 'urlopen', side_effect=error):
            self.assertIsNone(self.io.digest('1.2.3'))

    def test_malformed_404_response_is_not_registry_absence(self):
        import urllib.error
        error = urllib.error.HTTPError('https://registry', 404, '', {}, self.response(b'not JSON'))
        self.addCleanup(error.close)
        self.io.registry_token = 'fixture'
        with self.patch.object(urllib.request, 'urlopen', side_effect=error):
            with self.assertRaisesRegex(ValueError, 'Registry'):
                self.io.digest('1.2.3')

    def test_token_endpoint_404_does_not_prove_image_absence(self):
        import urllib.error
        error = urllib.error.HTTPError('https://auth.docker.io/token', 404, '', {},
                                      self.response(b'{"errors":[{"code":"MANIFEST_UNKNOWN"}]}'))
        with self.patch.object(urllib.request, 'urlopen', side_effect=error):
            with self.assertRaisesRegex(ValueError, 'Registry'):
                self.io.digest('1.2.3')

    def test_registry_hashes_exact_returned_manifest_bytes(self):
        import hashlib
        raw = b'{"schemaVersion":2,"config":{}}'
        self.io.registry_token = 'fixture'
        with self.patch.object(urllib.request, 'urlopen', return_value=self.response(raw)):
            self.assertEqual(self.io.digest('1.2.3'), 'sha256:' + hashlib.sha256(raw).hexdigest())

    def test_registry_timeout_is_not_absence(self):
        self.io.registry_token = 'fixture'
        with self.patch.object(urllib.request, 'urlopen', side_effect=TimeoutError()):
            with self.assertRaisesRegex(ValueError, 'Registry'):
                self.io.digest('1.2.3')

    def test_codeql_requires_exact_successful_master_push_and_all_three_jobs(self):
        run = {'id': 42, 'head_sha': SHA, 'head_branch': 'master', 'event': 'push',
               'conclusion': 'success', 'head_repository': {'full_name': 'owner/repo'}}
        jobs = [{'name': 'Analyze (' + language + ')', 'conclusion': 'success'}
                for language in ('java-kotlin', 'javascript-typescript', 'python')]
        with self.patch.object(self.io, 'api', side_effect=[[{'workflow_runs': [run]}], [{'jobs': jobs}]]):
            self.io.check_codeql(SHA)
        for field, value in (('head_sha', 'd' * 40), ('event', 'pull_request'),
                             ('conclusion', 'cancelled'), ('head_branch', 'feature')):
            invalid = {**run, field: value}
            with self.subTest(field=field), self.patch.object(self.io, 'api', return_value=[{'workflow_runs': [invalid]}]):
                with self.assertRaisesRegex(ValueError, 'CodeQL'):
                    self.io.check_codeql(SHA)
        for conclusion in ('skipped', 'failure', 'cancelled', None):
            invalid = [{**job, 'conclusion': conclusion} if index == 0 else job
                       for index, job in enumerate(jobs)]
            with self.subTest(conclusion=conclusion), self.patch.object(
                    self.io, 'api', side_effect=[[{'workflow_runs': [run]}], [{'jobs': invalid}]]):
                with self.assertRaisesRegex(ValueError, 'CodeQL'):
                    self.io.check_codeql(SHA)



if __name__ == '__main__':
    unittest.main()
