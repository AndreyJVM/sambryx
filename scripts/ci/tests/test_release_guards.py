"""Check publication guards before any external commands or filesystem writes."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from release_fixture import release

ROOT = Path(__file__).resolve().parents[3]


class ReleaseGuardsTest(unittest.TestCase):
    def environment(self):
        return {'GITHUB_ACTIONS': 'true', 'GITHUB_REPOSITORY': 'AndreyJVM/sambryx',
                'GITHUB_EVENT_NAME': 'push', 'GITHUB_REF': 'refs/tags/v1.2.3',
                'RELEASE_NEEDS': json.dumps({'quality-gate': {'result': 'success'},
                                            'build-and-test': {'result': 'success'}})}

    def test_only_canonical_tag_push_with_all_required_checks_can_publish(self):
        environment = self.environment()
        self.assertEqual(release.invocation(environment), '1.2.3')
        for key, value in (('GITHUB_ACTIONS', 'false'), ('GITHUB_REPOSITORY', 'other/repo'),
                           ('GITHUB_EVENT_NAME', 'workflow_dispatch'), ('GITHUB_REF', 'refs/heads/master'),
                           ('GITHUB_REF', 'refs/tags/v1.02.3'), ('RELEASE_NEEDS', '{}'),
                           ('RELEASE_NEEDS', 'invalid JSON')):
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                release.invocation({**environment, key: value})
        for job in ('quality-gate', 'build-and-test'):
            for status in ('failure', 'skipped', 'cancelled', None):
                needs = json.loads(environment['RELEASE_NEEDS'])
                needs[job]['result'] = status
                with self.subTest(job=job, status=status), self.assertRaises(ValueError):
                    release.invocation({**environment, 'RELEASE_NEEDS': json.dumps(needs)})

    def test_invalid_cli_environment_never_reaches_external_commands(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            binary = directory / 'gh'
            binary.write_text('#!/bin/sh\ntouch "$CALLS"\nexit 99\n')
            binary.chmod(0o755)
            calls = directory / 'calls'
            result = subprocess.run(['python3', '-B', str(ROOT / 'scripts/ci/release/release.py'), 'publish'],
                                    cwd=directory, env={**os.environ, **self.environment(),
                                        'GITHUB_EVENT_NAME': 'workflow_dispatch', 'CALLS': str(calls),
                                        'PATH': str(directory) + os.pathsep + os.environ['PATH']},
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 1)
            self.assertIn('Release stopped:', result.stderr)
            self.assertFalse(calls.exists())
            self.assertFalse((directory / 'release-work').exists())

    def test_checkout_mismatch_stops_before_publication(self):
        environment = {**self.environment(), 'GITHUB_SHA': 'a' * 40}
        with patch.dict(os.environ, environment), patch.object(release.sys, 'argv', ['release.py', 'publish']), patch.object(
                release.Services, 'api', return_value={'sha': 'a' * 40}), patch.object(
                release.Services, 'command', return_value=b'different-head'), patch.object(release, 'publish') as publish:
            self.assertEqual(release.main(), 1)
        publish.assert_not_called()
