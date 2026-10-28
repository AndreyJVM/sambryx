"""Check release CLI guards before any external command can mutate state."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class ReleaseCommandGuardTest(unittest.TestCase):
    def test_invalid_invocations_never_reach_external_commands(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            calls = directory / 'calls'
            binary = directory / 'gh'
            binary.write_text('#!/bin/sh\nprintf called >> "$CALLS"\nexit 99\n')
            binary.chmod(0o755)
            valid = {'GITHUB_ACTIONS': 'true', 'GITHUB_REPOSITORY': 'AndreyJVM/sambryx',
                     'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_REF': 'refs/heads/master'}
            for operation, version, updates in (
                    ('repair', '1.2.3;id', {}), ('repair', '1.2.3-rc1', {}),
                    ('repair', '1.2.3', {'GITHUB_REF': 'refs/heads/feature'}),
                    ('repair', '1.2.3', {'GITHUB_EVENT_NAME': 'pull_request'}),
                    ('publish', '1.2.3', {'GITHUB_EVENT_NAME': 'push', 'GITHUB_REF': 'refs/tags/v1.2.3',
                                         'RELEASE_NEEDS': json.dumps({'quality-gate': {'result': 'skipped'}})})):
                with self.subTest(operation=operation, version=version, updates=updates):
                    result = subprocess.run(['python3', str(ROOT / 'scripts/ci/release.py'), operation,
                                             '--version', version], cwd=directory, env={
                        **os.environ, **valid, **updates, 'PATH': str(directory) + os.pathsep + os.environ['PATH'],
                        'CALLS': str(calls)}, capture_output=True, text=True)
                    self.assertEqual(result.returncode, 1, result.stderr)
                    self.assertIn('Release stopped:', result.stderr)
                    self.assertFalse(calls.exists())
                    self.assertFalse((directory / 'release-work').exists())


if __name__ == '__main__':
    unittest.main()
