"""Exercise stable release transitions with external command/API doubles."""

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('release', Path(__file__).with_name('release.py'))
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)
SHA = 'a' * 40
DIGEST = 'sha256:' + 'b' * 64
OTHER = 'sha256:' + 'c' * 64


class Boundary:
    """Model remote state; the production coordinator still executes every transition."""

    def __init__(self):
        self.record = None
        self.draft = True
        self.tags = {}
        self.published = []
        self.calls = []
        self.failure = None
        self.conflict = False

    def check_codeql(self, sha):
        self.calls.append('codeql')
        if self.failure == 'codeql':
            raise ValueError('CodeQL not successful')

    def load(self, version, sha, directory):
        self.calls.append('load')
        if self.conflict:
            raise ValueError('Existing asset conflict')
        return self.record

    def prepare(self, version, sha, directory):
        self.calls.append('prepare')
        return {'version': version, 'source_sha': sha, 'image_digest': DIGEST}

    def persist(self, record, directory):
        self.calls.append('persist')
        self.record = record
        if self.failure == 'persist':
            raise ValueError('Upload interrupted')

    def verify(self, record, directory):
        self.calls.append('verify')
        if self.failure == 'jar':
            raise ValueError('JAR checksum mismatch')

    def digest(self, tag):
        self.calls.append('digest:' + tag)
        if self.failure == 'registry':
            raise ValueError('Registry unavailable')
        return self.tags.get(tag)

    def copy_version(self, record, directory):
        self.calls.append('version')
        self.tags[record['version']] = OTHER if self.failure == 'digest' else DIGEST
        if self.failure == 'push':
            raise ValueError('Push interrupted')

    def verify_published(self, record, directory):
        self.calls.append('remote-smoke')
        if self.failure == 'remote-jar':
            raise ValueError('Published image contains a different JAR')

    def publish(self, record):
        self.calls.append('publish')
        if self.failure == 'github':
            raise ValueError('GitHub unavailable')
        self.draft = False
        if record['version'] not in self.published:
            self.published.append(record['version'])

    def versions(self):
        return self.published

    def tag_digests(self):
        return self.tags.copy()

    def copy_alias(self, alias, digest):
        self.calls.append('alias:' + alias)
        if self.failure == alias:
            raise ValueError('Alias interrupted')
        self.tags[alias] = OTHER if self.failure == 'alias-digest' else digest


class ReleaseTransitionsTest(unittest.TestCase):
    def setUp(self):
        self.boundary = Boundary()

    def run_release(self, version='0.10.0', repair=False):
        return release.coordinate(self.boundary, version, SHA, Path('.'), repair=repair)

    def test_order_and_final_digest(self):
        self.run_release()
        calls = self.boundary.calls
        for before, after in [('codeql', 'persist'), ('persist', 'version'),
                              ('version', 'publish'), ('publish', 'alias:latest')]:
            self.assertLess(calls.index(before), calls.index(after))
        self.assertEqual(self.boundary.tags,
                         {'0.10.0': DIGEST, 'latest': DIGEST, '0.10': DIGEST})

    def test_retry_after_each_partial_failure_reuses_record(self):
        for failure in ('persist', 'push', 'github', 'latest', '0.10'):
            with self.subTest(failure=failure):
                self.boundary = Boundary()
                self.boundary.failure = failure
                with self.assertRaises(ValueError):
                    self.run_release()
                self.boundary.failure = None
                self.boundary.calls.clear()
                self.run_release()
                self.assertNotIn('prepare', self.boundary.calls)
                self.assertEqual(self.boundary.tags['latest'], DIGEST)
                if failure in ('github', 'latest', '0.10'):
                    self.assertNotIn('version', self.boundary.calls)

    def test_conflicts_and_external_failures_do_not_publish_following_steps(self):
        for failure in ('codeql', 'registry', 'jar', 'digest', 'remote-jar'):
            with self.subTest(failure=failure):
                self.boundary = Boundary()
                self.boundary.failure = failure
                with self.assertRaises(ValueError):
                    self.run_release()
                self.assertNotIn('publish', self.boundary.calls)
                self.assertNotIn('alias:latest', self.boundary.calls)

    def test_existing_version_conflict_is_never_overwritten(self):
        self.boundary.tags['0.10.0'] = OTHER
        with self.assertRaisesRegex(ValueError, 'conflict'):
            self.run_release()
        self.assertNotIn('version', self.boundary.calls)
        self.assertNotIn('publish', self.boundary.calls)

    def test_existing_asset_conflict_stops_before_mutation(self):
        self.boundary.conflict = True
        with self.assertRaisesRegex(ValueError, 'conflict'):
            self.run_release()
        self.assertNotIn('persist', self.boundary.calls)

    def test_unknown_alias_identity_fails_closed(self):
        self.boundary.tags['latest'] = OTHER
        with self.assertRaisesRegex(ValueError, 'alias'):
            self.run_release()
        self.assertNotIn('alias:latest', self.boundary.calls)

    def test_alias_digest_is_verified(self):
        self.boundary.failure = 'alias-digest'
        with self.assertRaisesRegex(ValueError, 'digest'):
            self.run_release()

    def test_numeric_semver_and_older_retry_preserve_both_aliases(self):
        self.boundary.tags = {'0.10.0': OTHER, 'latest': OTHER, '0.10': OTHER}
        self.boundary.published = ['0.10.0']
        self.run_release('0.9.9')
        self.assertEqual(self.boundary.tags['latest'], OTHER)
        self.assertEqual(self.boundary.tags['0.9'], DIGEST)
        self.boundary.record = None
        self.boundary.tags = {'0.10.2': OTHER, 'latest': OTHER, '0.10': OTHER}
        self.boundary.published = ['0.10.2']
        self.run_release('0.10.1')
        self.assertEqual(self.boundary.tags['latest'], OTHER)
        self.assertEqual(self.boundary.tags['0.10'], OTHER)

    def test_manual_repair_requires_existing_public_record_and_never_pushes_version(self):
        with self.assertRaises(ValueError):
            self.run_release(repair=True)
        self.assertNotIn('prepare', self.boundary.calls)
        self.assertNotIn('load', self.boundary.calls)
        self.boundary.record = {'version': '0.10.0', 'source_sha': SHA, 'image_digest': DIGEST}
        self.boundary.tags['0.10.0'] = DIGEST
        self.boundary.published = ['0.10.0']
        self.boundary.calls.clear()
        self.run_release(repair=True)
        self.assertNotIn('version', self.boundary.calls)
        self.assertNotIn('publish', self.boundary.calls)
        self.assertEqual(self.boundary.tags['0.10'], DIGEST)

    def test_only_canonical_stable_versions_are_accepted_before_boundaries(self):
        for version in ('0.01.0', '1.2.3-rc1', 'v1.2.3', '1.2', '1.2.3;id'):
            with self.subTest(version=version), self.assertRaises(ValueError):
                self.run_release(version)
        self.assertEqual(self.boundary.calls, [])


class ExternalBoundaryTest(unittest.TestCase):
    def setUp(self):
        from unittest.mock import patch
        self.patch = patch
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.io = release.Services('owner/repo', self.directory)

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
            with self.subTest(status=status), self.patch.object(release.urllib.request, 'urlopen', side_effect=error):
                self.io.registry_token = 'fixture'
                with self.assertRaises(ValueError):
                    self.io.digest('1.2.3')
        error = urllib.error.HTTPError('https://registry', 404, '', {}, self.response(
            b'{"errors":[{"code":"MANIFEST_UNKNOWN"}]}'))
        with self.patch.object(release.urllib.request, 'urlopen', side_effect=error):
            self.assertIsNone(self.io.digest('1.2.3'))

    def test_malformed_404_response_is_not_registry_absence(self):
        import urllib.error
        error = urllib.error.HTTPError('https://registry', 404, '', {}, self.response(b'not JSON'))
        self.addCleanup(error.close)
        self.io.registry_token = 'fixture'
        with self.patch.object(release.urllib.request, 'urlopen', side_effect=error):
            with self.assertRaisesRegex(ValueError, 'Registry'):
                self.io.digest('1.2.3')

    def test_registry_hashes_exact_returned_manifest_bytes(self):
        import hashlib
        raw = b'{"schemaVersion":2,"config":{}}'
        self.io.registry_token = 'fixture'
        with self.patch.object(release.urllib.request, 'urlopen', return_value=self.response(raw)):
            self.assertEqual(self.io.digest('1.2.3'), 'sha256:' + hashlib.sha256(raw).hexdigest())

    def test_registry_timeout_is_not_absence(self):
        self.io.registry_token = 'fixture'
        with self.patch.object(release.urllib.request, 'urlopen', side_effect=TimeoutError()):
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

    def test_asset_hash_conflict_and_missing_archive_stop_without_upload(self):
        record = {'schema': 1, 'version': '1.2.3', 'source_sha': SHA,
                  'image': release.IMAGE, 'image_digest': DIGEST,
                  'jar_sha256': 'b' * 64, 'archive_sha256': 'c' * 64}
        remote = {'id': 1, 'tag_name': 'v1.2.3', 'draft': True,
                  'body': release.record_body(record)}
        for assets in ([], [{'name': 'application.jar', 'state': 'uploaded', 'id': 2}],
                       [{'name': 'release.json', 'state': 'uploaded', 'id': 3}]):
            with self.subTest(assets=assets), self.patch.object(self.io, 'releases', return_value=[remote]), self.patch.object(
                    self.io, 'assets', return_value=assets), self.patch.object(self.io, 'download', return_value=b'wrong'), self.patch.object(
                    self.io, 'command') as command:
                with self.assertRaises(ValueError):
                    self.io.load('1.2.3', SHA, self.directory)
                command.assert_not_called()

    def test_existing_record_cannot_change_source_or_digest_format(self):
        record = {'schema': 1, 'version': '1.2.3', 'source_sha': 'd' * 40,
                  'image': release.IMAGE, 'image_digest': DIGEST,
                  'jar_sha256': 'b' * 64, 'archive_sha256': 'c' * 64}
        with self.assertRaisesRegex(ValueError, 'source'):
            release.validate_record(record, '1.2.3', SHA)
        record['source_sha'] = SHA
        record['image_digest'] = 'invalid'
        with self.assertRaises(ValueError):
            release.validate_record(record, '1.2.3', SHA)


class ReleaseInvocationTest(unittest.TestCase):
    def test_publish_guard_rejects_every_non_successful_dependency_and_event(self):
        environment = {'GITHUB_ACTIONS': 'true', 'GITHUB_REPOSITORY': 'AndreyJVM/sambryx',
                       'GITHUB_EVENT_NAME': 'push', 'GITHUB_REF': 'refs/tags/v1.2.3',
                       'RELEASE_NEEDS': json.dumps({'quality-gate': {'result': 'success'},
                                                    'build-and-test': {'result': 'success'}})}
        self.assertEqual(release.invocation(environment, 'publish', None), '1.2.3')
        for result in ('skipped', 'cancelled', 'failure', None):
            for job in ('quality-gate', 'build-and-test'):
                needs = json.loads(environment['RELEASE_NEEDS'])
                needs[job]['result'] = result
                with self.subTest(result=result, job=job), self.assertRaises(ValueError):
                    release.invocation({**environment, 'RELEASE_NEEDS': json.dumps(needs)}, 'publish', None)
        for key, value in (('GITHUB_ACTIONS', 'false'), ('GITHUB_EVENT_NAME', 'pull_request'),
                           ('GITHUB_REF', 'refs/heads/master'), ('RELEASE_NEEDS', '{}'),
                           ('GITHUB_REPOSITORY', 'fork/repo')):
            with self.subTest(key=key), self.assertRaises(ValueError):
                release.invocation({**environment, key: value}, 'publish', None)

    def test_repair_guard_requires_manual_master_and_canonical_version(self):
        environment = {'GITHUB_ACTIONS': 'true', 'GITHUB_REPOSITORY': 'AndreyJVM/sambryx',
                       'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_REF': 'refs/heads/master'}
        self.assertEqual(release.invocation(environment, 'repair', '1.2.3'), '1.2.3')
        with self.assertRaises(ValueError):
            release.invocation({**environment, 'GITHUB_REF': 'refs/tags/v1.2.3'}, 'repair', '1.2.3')


class RecoveryAssetTest(unittest.TestCase):
    def test_missing_draft_assets_recover_original_bytes_without_rebuild_or_replacement(self):
        import hashlib
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            jar, archive = b'canonical jar', b'canonical archive'
            record = {'schema': 1, 'version': '1.2.3', 'source_sha': SHA, 'image': release.IMAGE,
                      'image_digest': DIGEST, 'jar_sha256': hashlib.sha256(jar).hexdigest(),
                      'archive_sha256': hashlib.sha256(archive).hexdigest(),
                      'run_id': '123', 'run_attempt': '1'}
            remote = {'id': 1, 'tag_name': 'v1.2.3', 'draft': True, 'body': release.record_body(record)}
            io = release.Services('owner/repo', directory)

            def restore(*args):
                self.assertEqual(args[:4], ('gh', 'run', 'download', '123'))
                self.assertIn('release-recovery-123-1', args)
                destination = Path(args[args.index('--dir') + 1])
                destination.mkdir(exist_ok=True)
                (destination / 'application.jar').write_bytes(jar)
                (destination / 'image.oci.tar').write_bytes(archive)
                (destination / 'release.json').write_bytes(release.encoded(record))
                return b''

            with patch.object(io, 'releases', return_value=[remote]), patch.object(io, 'assets', return_value=[]), patch.object(
                    io, 'command', side_effect=restore), patch.object(io, 'persist') as persist:
                self.assertEqual(io.load('1.2.3', SHA, directory), record)
                persist.assert_called_once()
                self.assertEqual((directory / 'image.oci.tar').read_bytes(), archive)


class PublicationPolicyRegressionTest(unittest.TestCase):
    def test_token_endpoint_404_is_never_treated_as_missing_manifest(self):
        import io
        import urllib.error
        from unittest.mock import patch
        services = release.Services('owner/repo', Path('.'))
        error = urllib.error.HTTPError('https://auth.docker.io/token', 404, '', {},
                                      io.BytesIO(b'{"errors":[{"code":"MANIFEST_UNKNOWN"}]}'))
        self.addCleanup(error.close)
        with patch.object(release.urllib.request, 'urlopen', side_effect=error):
            with self.assertRaisesRegex(ValueError, 'Registry'):
                services.digest('1.2.3')

    def test_new_public_release_marks_github_latest_using_numeric_version_order(self):
        from unittest.mock import patch
        for previous, target, expected in (('0.9.9', '0.10.0', '--latest=true'),
                                           ('0.10.0', '0.9.9', '--latest=false')):
            services = release.Services('owner/repo', Path('.'))
            services.remote = {'draft': True}
            record = {'version': target, 'source_sha': SHA}
            with self.subTest(previous=previous), patch.object(services, 'load'), patch.object(
                    services, 'versions', side_effect=[[previous], [previous, target]]), patch.object(services, 'command') as command:
                services.publish(record)
                self.assertIn(expected, command.call_args.args)


class ReleaseNotesTest(unittest.TestCase):
    def test_recovery_record_can_precede_generated_release_notes(self):
        import hashlib
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            files = {'application.jar': b'jar', 'image.oci.tar': b'archive'}
            record = {'schema': 1, 'version': '1.2.3', 'source_sha': SHA, 'image': release.IMAGE,
                      'image_digest': DIGEST, 'jar_sha256': hashlib.sha256(files['application.jar']).hexdigest(),
                      'archive_sha256': hashlib.sha256(files['image.oci.tar']).hexdigest()}
            files['release.json'] = release.encoded(record)
            remote = {'id': 1, 'tag_name': 'v1.2.3', 'draft': False,
                      'body': release.record_body(record) + '\n## Changes\nGenerated notes\n'}
            services = release.Services('owner/repo', directory)
            assets = [{'id': name, 'name': name, 'state': 'uploaded'} for name in files]
            with patch.object(services, 'releases', return_value=[remote]), patch.object(
                    services, 'assets', return_value=assets), patch.object(
                    services, 'download', side_effect=lambda asset: files[asset['name']]):
                self.assertEqual(services.load('1.2.3', SHA, directory), record)


class SupersededAliasTest(unittest.TestCase):
    def test_older_retry_requires_verified_newer_alias_instead_of_reporting_missing_as_complete(self):
        boundary = Boundary()
        boundary.tags = {'0.10.0': OTHER}
        boundary.published = ['0.10.0']
        with self.assertRaisesRegex(ValueError, '0.10.0'):
            release.coordinate(boundary, '0.9.9', SHA, Path('.'))
        self.assertNotIn('alias:latest', boundary.calls)

    def test_alias_pointing_to_unpublished_newer_version_is_never_rolled_back(self):
        boundary = Boundary()
        boundary.tags = {'0.11.0': OTHER, 'latest': OTHER}
        with self.assertRaisesRegex(ValueError, 'alias'):
            release.coordinate(boundary, '0.10.0', SHA, Path('.'))
        self.assertNotIn('alias:latest', boundary.calls)


class ReleaseSummaryTest(unittest.TestCase):
    def test_failure_before_preparation_reports_source_sha_and_reason(self):
        import contextlib
        import io
        import os
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as temporary:
            original = Path.cwd()
            try:
                os.chdir(temporary)
                summary = Path(temporary) / 'summary.md'
                environment = {'GITHUB_ACTIONS': 'true', 'GITHUB_REPOSITORY': 'AndreyJVM/sambryx',
                               'GITHUB_EVENT_NAME': 'push', 'GITHUB_REF': 'refs/tags/v1.2.3', 'GITHUB_SHA': SHA,
                               'RELEASE_NEEDS': json.dumps({'quality-gate': {'result': 'success'},
                                                            'build-and-test': {'result': 'success'}}),
                               'GITHUB_STEP_SUMMARY': str(summary)}
                with patch.dict(os.environ, environment), patch.object(release.sys, 'argv', ['release.py', 'publish']), patch.object(
                        release.Services, 'api', return_value={'sha': SHA}), patch.object(
                        release.Services, 'command', return_value=SHA.encode()), patch.object(
                        release.Services, 'check_codeql', side_effect=ValueError('CodeQL pending')), contextlib.redirect_stdout(
                        io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(release.main(), 1)
                self.assertIn(SHA, summary.read_text())
                self.assertIn('CodeQL pending', summary.read_text())
            finally:
                os.chdir(original)


class LegacyBaselineTest(unittest.TestCase):
    def setUp(self):
        import hashlib
        from unittest.mock import patch
        self.patch = patch
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.services = release.Services('owner/repo', self.directory, repair=True)
        self.jar = b'legacy release JAR'
        self.record = {'schema': 1, 'version': '0.12.0', 'source_sha': SHA, 'image': release.IMAGE,
                       'image_digest': DIGEST, 'jar_sha256': hashlib.sha256(self.jar).hexdigest(),
                       'legacy': True, 'release_id': 1, 'jar_asset_id': 2, 'jar_asset_name': 'legacy.jar'}
        self.remote = {'id': 1, 'tag_name': 'v0.12.0', 'draft': False, 'prerelease': False, 'body': 'Legacy notes'}
        self.asset = {'id': 2, 'name': 'legacy.jar', 'state': 'uploaded'}

    def load(self):
        with self.patch.object(self.services, 'releases', return_value=[self.remote]), self.patch.object(
                self.services, 'assets', return_value=[self.asset]), self.patch.object(
                self.services, 'download', return_value=self.jar), self.patch.object(
                self.services, 'legacy_baseline', return_value=self.record):
            return self.services.load('0.12.0', SHA, self.directory)

    def test_approved_legacy_baseline_loads_exact_jar_without_remote_writes_or_archive(self):
        with self.patch.object(self.services, 'command') as command:
            self.assertEqual(self.load(), self.record)
            command.assert_not_called()
        self.assertEqual((self.directory / 'application.jar').read_bytes(), self.jar)
        with self.patch.object(self.services, 'command') as command:
            self.services.verify(self.record, self.directory)
            command.assert_not_called()
        self.assertFalse((self.directory / 'image.oci.tar').exists())

    def test_legacy_source_asset_and_checksum_conflicts_fail_before_commands(self):
        for field, value in (('source_sha', 'd' * 40), ('release_id', 9),
                             ('jar_asset_id', 9), ('jar_sha256', 'd' * 64)):
            original = self.record[field]
            self.record[field] = value
            with self.subTest(field=field), self.patch.object(self.services, 'command') as command:
                with self.assertRaises(ValueError):
                    self.load()
                command.assert_not_called()
            self.record[field] = original

    def test_legacy_baseline_is_for_repair_only_and_not_generic_migration(self):
        self.services.repair = False
        with self.assertRaises(ValueError):
            self.load()
        self.services.repair = True
        self.remote['tag_name'] = 'v0.11.0'
        with self.patch.object(self.services, 'releases', return_value=[self.remote]), self.patch.object(
                self.services, 'legacy_baseline') as baseline:
            with self.assertRaises(ValueError):
                self.services.load('0.11.0', SHA, self.directory)
            baseline.assert_not_called()

    def test_legacy_retry_preserves_newer_latest_and_verifies_remote_jar(self):
        boundary = Boundary()
        boundary.record = self.record
        boundary.published = ['0.12.0', '0.13.0']
        boundary.tags = {'0.12.0': DIGEST, '0.12': DIGEST, '0.13.0': OTHER, 'latest': OTHER}
        release.coordinate(boundary, '0.12.0', SHA, self.directory, repair=True)
        self.assertIn('remote-smoke', boundary.calls)
        self.assertEqual(boundary.tags['latest'], OTHER)
        self.assertEqual(boundary.tags['0.12'], DIGEST)
        self.assertNotIn('version', boundary.calls)
        self.assertNotIn('publish', boundary.calls)
        self.assertNotIn('alias:latest', boundary.calls)

    def test_baseline_digest_conflict_blocks_aliases_and_preserves_legacy_release(self):
        boundary = Boundary()
        boundary.record = self.record
        boundary.published = ['0.12.0']
        boundary.tags = {'0.12.0': OTHER}
        with self.assertRaisesRegex(ValueError, 'conflict'):
            release.coordinate(boundary, '0.12.0', SHA, self.directory, repair=True)
        self.assertNotIn('version', boundary.calls)
        self.assertNotIn('publish', boundary.calls)
        self.assertNotIn('alias:latest', boundary.calls)


if __name__ == '__main__':
    unittest.main()
