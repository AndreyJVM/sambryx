"""Execute alias workflow shell blocks with mocked external boundaries."""

import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
DIGEST = 'sha256:' + 'a' * 64


class AliasRepairTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        binary = self.directory / 'bin'
        binary.mkdir()
        self.calls = self.directory / 'calls'
        self.environment = {**os.environ, 'PATH': str(binary) + os.pathsep + os.environ['PATH'],
                            'CALLS': str(self.calls), 'RELEASE_VERSION': '1.2.3',
                            'IMAGE': 'example/app', 'STANDARD_DIGEST': DIGEST,
                            'GITHUB_OUTPUT': str(self.directory / 'output')}
        mock = '''#!/usr/bin/env python3
import json, os, pathlib, sys
args = sys.argv[1:]
name = pathlib.Path(sys.argv[0]).name
with open(os.environ['CALLS'], 'a') as output:
    output.write(json.dumps([name] + args) + '\\n')
if name == 'gh':
    directory = pathlib.Path('release-artifacts')
    directory.mkdir()
    for index in range(int(os.environ.get('JAR_COUNT', '1'))):
        (directory / (str(index) + '.jar')).write_text('fixture')
elif name == 'docker':
    if any('astra' in arg for arg in args):
        sys.exit(19)
    if 'create' in args:
        sys.exit(int(os.environ.get('CREATE_EXIT', '0')))
    if 'inspect' in args:
        print(os.environ.get('VERSION_DIGEST' if 'example/app:1.2.3' in args else 'SOURCE_DIGEST', 'sha256:' + 'a' * 64))
elif name == 'bash':
    sys.exit(int(os.environ.get('SMOKE_EXIT', '0')))
'''
        for name in ('gh', 'docker', 'bash'):
            path = binary / name
            path.write_text(mock)
            path.chmod(0o755)
        workflow = (ROOT / '.github/workflows/docker-aliases.yml').read_text()
        self.blocks = [match.group(1) for match in re.finditer(
            r'        run: \|\n((?:          [^\n]*\n|\n)+)', workflow)]

    def run_block(self, index):
        code = '\n'.join(line[10:] for line in self.blocks[index].splitlines())
        return subprocess.run(['/bin/bash', '-euo', 'pipefail', '-c', code],
                              cwd=self.directory, env=self.environment, capture_output=True, text=True)

    def calls_read(self):
        return [json.loads(line) for line in self.calls.read_text().splitlines()] if self.calls.exists() else []

    def test_standard_source_verified_without_astra(self):
        result = self.run_block(1)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(['docker', 'pull', 'example/app@' + DIGEST], self.calls_read())
        self.assertIn(['bash', 'scripts/ci/smoke-docker.sh', 'example/app@' + DIGEST,
                       'release-artifacts/0.jar'], self.calls_read())
        self.assertEqual((self.directory / 'output').read_text(), 'standard=' + DIGEST + '\n')

    def test_invalid_version_stops_before_external_calls(self):
        self.environment['RELEASE_VERSION'] = '1.2.3; echo unsafe'
        self.assertNotEqual(self.run_block(0).returncode, 0)
        self.assertEqual(self.calls_read(), [])

    def test_missing_or_multiple_jars_stop_before_docker(self):
        for count in ('0', '2'):
            with self.subTest(count=count):
                self.environment['JAR_COUNT'] = count
                self.assertNotEqual(self.run_block(1).returncode, 0)
                self.assertFalse(any(call[0] == 'docker' for call in self.calls_read()))
                for jar in (self.directory / 'release-artifacts').glob('*.jar'):
                    jar.unlink()
                (self.directory / 'release-artifacts').rmdir()

    def test_invalid_digest_stops_before_pull(self):
        self.environment['VERSION_DIGEST'] = 'invalid'
        self.assertNotEqual(self.run_block(1).returncode, 0)
        self.assertFalse(any('pull' in call for call in self.calls_read()))

    def test_smoke_failure_does_not_emit_verified_source(self):
        self.environment['SMOKE_EXIT'] = '23'
        self.assertEqual(self.run_block(1).returncode, 23)
        self.assertFalse((self.directory / 'output').exists())

    def test_restore_checks_latest_and_preserves_version(self):
        result = self.run_block(2)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.calls_read(), [
            ['docker', 'buildx', 'imagetools', 'create', '--prefer-index=false', '--tag',
             'example/app:latest', 'example/app@' + DIGEST],
            ['docker', 'buildx', 'imagetools', 'inspect', 'example/app:latest', '--format', '{{.Manifest.Digest}}'],
            ['docker', 'buildx', 'imagetools', 'inspect', 'example/app:1.2.3', '--format', '{{.Manifest.Digest}}']])

    def test_failed_create_stops_verification(self):
        self.environment['CREATE_EXIT'] = '17'
        self.assertEqual(self.run_block(2).returncode, 17)
        self.assertEqual(len(self.calls_read()), 1)

    def test_version_digest_mismatch_fails(self):
        self.environment['VERSION_DIGEST'] = 'sha256:' + 'b' * 64
        self.assertNotEqual(self.run_block(2).returncode, 0)

    def test_alias_digest_mismatch_fails(self):
        self.environment['SOURCE_DIGEST'] = 'sha256:' + 'b' * 64
        self.assertNotEqual(self.run_block(2).returncode, 0)


if __name__ == '__main__':
    unittest.main()
