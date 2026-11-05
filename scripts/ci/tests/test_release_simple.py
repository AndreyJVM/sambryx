"""Check one-way release publication without real registry or GitHub writes."""

import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / 'release/release.py'
SPEC = importlib.util.spec_from_file_location('simple_release', SCRIPT)
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)
SHA = 'a' * 40
DIGEST = 'sha256:' + 'b' * 64


class SimpleReleaseTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.jar = self.directory / 'tested.jar'
        self.jar.write_bytes(b'tested application')
        self.work = self.directory / 'release-work'
        self.services = release.Services('owner/repo')
        self.calls = []
        self.failure = None
        self.existing = []
        self.public = False
        self.command = patch.object(self.services, 'command', side_effect=self.run_command)
        self.command.start()
        self.addCleanup(self.command.stop)

    def run_command(self, *args):
        self.calls.append(args)
        if self.failure and self.failure(args):
            raise ValueError('Synthetic external failure')
        if args[:2] == ('gh', 'release') and args[2] == 'edit':
            self.public = True
        if args[:2] == ('bash', 'scripts/ci/release/select-jar.sh'):
            return str(self.jar).encode()
        return b''

    def releases(self):
        return self.existing + ([{'tag_name': 'v1.2.3', 'draft': False, 'prerelease': False}] if self.public else [])

    def publish(self, digests=None):
        with patch.object(self.services, 'releases', side_effect=self.releases), patch.object(
                self.services, 'check_codeql'), patch.object(
                self.services, 'digest', side_effect=digests or [None, DIGEST, DIGEST]):
            return release.publish(self.services, '1.2.3', SHA, self.work)

    def test_success_publishes_the_tested_jar_and_only_the_versioned_image(self):
        record = self.publish()
        self.assertEqual((self.work / 'application.jar').read_bytes(), self.jar.read_bytes())
        self.assertEqual(record['jar_sha256'], hashlib.sha256(self.jar.read_bytes()).hexdigest())
        self.assertEqual(record['image_digest'], DIGEST)
        self.assertEqual(json.loads((self.work / 'release.json').read_text()), record)
        self.assertIn(record['jar_sha256'], (self.work / 'SHA256SUMS').read_text())
        pushes = [args for args in self.calls if args[:2] == ('docker', 'push')]
        self.assertEqual(pushes, [('docker', 'push', release.IMAGE + ':1.2.3')])
        pull = ('docker', 'pull', release.IMAGE + '@' + DIGEST)
        self.assertIn(pull, self.calls)
        edit = next(args for args in self.calls if args[:3] == ('gh', 'release', 'edit'))
        self.assertLess(self.calls.index(pull), self.calls.index(edit))
        self.assertTrue(self.public)

    def test_existing_release_including_drafts_blocks_all_commands(self):
        for draft in (True, False):
            self.existing = [{'tag_name': 'v1.2.3', 'draft': draft}]
            with self.subTest(draft=draft), self.assertRaisesRegex(ValueError, 'already exists'):
                self.publish()
            self.assertEqual(self.calls, [])

    def test_existing_image_blocks_build_and_publication(self):
        with self.assertRaisesRegex(ValueError, 'already exists'):
            self.publish([DIGEST])
        self.assertEqual(self.calls, [])

    def test_codeql_failure_stops_before_commands(self):
        with patch.object(self.services, 'check_codeql', side_effect=ValueError('CodeQL failed')):
            with self.assertRaisesRegex(ValueError, 'CodeQL'):
                release.publish(self.services, '1.2.3', SHA, self.work)
        self.assertEqual(self.calls, [])

    def test_failures_never_make_the_release_public_or_continue_to_later_steps(self):
        smoke = ('bash', './mvnw', '-B', '-f', 'tools/smoke/pom.xml', '-Pdocker-smoke', 'verify')
        stages = {
            'build': ('docker', 'build'),
            'local-smoke': (*smoke, '-Dsmoke.image=' + release.IMAGE + ':1.2.3'),
            'draft': ('gh', 'release', 'create'),
            'push': ('docker', 'push'),
            'pull': ('docker', 'pull'),
            'remote-smoke': (*smoke, '-Dsmoke.image=' + release.IMAGE + '@' + DIGEST),
            'upload': ('gh', 'release', 'upload'),
        }
        for stage, prefix in stages.items():
            with self.subTest(stage=stage):
                self.calls.clear()
                self.public = False
                self.failure = lambda args: args[:len(prefix)] == prefix
                with self.assertRaisesRegex(ValueError, 'Synthetic'):
                    self.publish()
                self.assertTrue(self.failure(self.calls[-1]))
                self.assertFalse(self.public)

    def test_missing_or_changed_registry_digest_blocks_public_release(self):
        for values in ([None, None], [None, DIGEST, 'sha256:' + 'c' * 64]):
            with self.subTest(values=values), self.assertRaisesRegex(ValueError, 'digest'):
                self.publish(values)
            self.assertFalse(self.public)

    def test_invalid_version_is_rejected_before_any_commands(self):
        for version in ('1.02.3', '1.2.3-rc1', '1.2.3;id'):
            with self.subTest(version=version), self.assertRaises(ValueError):
                release.publish(self.services, version, SHA, self.work)
        self.assertEqual(self.calls, [])

    def test_older_release_does_not_replace_github_latest(self):
        self.existing = [{'tag_name': 'v1.10.0', 'draft': False, 'prerelease': False}]
        self.publish()
        edit = next(args for args in self.calls if args[:3] == ('gh', 'release', 'edit'))
        self.assertIn('--latest=false', edit)

    def test_repair_cli_has_been_removed(self):
        result = subprocess.run(['python3', '-B', str(SCRIPT), 'repair', '--version', '1.2.3'],
                                cwd=self.directory, capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)
        self.assertFalse(self.work.exists())


if __name__ == '__main__':
    unittest.main()
