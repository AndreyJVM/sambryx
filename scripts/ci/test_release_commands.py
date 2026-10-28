"""Run the real coordinator/CLI adapters against subprocess and HTTP doubles."""

import base64
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.error

SPEC = importlib.util.spec_from_file_location('release_commands', Path(__file__).with_name('release.py'))
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)
SHA = 'a' * 40
RAW = b'{"schemaVersion":2,"fixture":"canonical image"}'
DIGEST = 'sha256:' + hashlib.sha256(RAW).hexdigest()

# These programs simulate only the external tools. Production orchestration and asset
# checks execute unchanged and share durable remote state across separate invocations.
PROGRAM = r'''#!/usr/bin/env python3
import base64, hashlib, json, os, pathlib, sys
state_file = pathlib.Path(os.environ['FIXTURE_STATE'])
state = json.loads(state_file.read_text())
name = pathlib.Path(sys.argv[0]).name
args = sys.argv[1:]
state['calls'].append([name] + args)
raw = b'{"schemaVersion":2,"fixture":"canonical image"}'
digest = 'sha256:' + hashlib.sha256(raw).hexdigest()
output = b''
failed = False
if name == 'gh':
    if args[0] == 'api':
        path = args[1]
        if path.endswith('/releases/generate-notes'):
            output = b'{"body":"Generated release notes"}'
        elif '/releases/assets/' in path:
            output = base64.b64decode(state['assets'][int(path.split('/')[-1]) - 1]['data'])
        elif '/assets?' in path:
            output = json.dumps([{k: v for k, v in item.items() if k != 'data'} for item in state['assets']]).encode()
        elif '/releases?' in path:
            output = json.dumps(state['releases']).encode()
        elif '/workflows/codeql.yml/runs?' in path:
            output = json.dumps({'workflow_runs': [{'id': 42, 'head_sha': 'a' * 40,
                'head_branch': 'master', 'event': 'push', 'conclusion': 'success',
                'head_repository': {'full_name': 'owner/repo'}}]}).encode()
        elif '/actions/runs/42/jobs?' in path:
            output = json.dumps({'jobs': [{'name': 'Analyze (' + language + ')', 'conclusion': 'success'}
                for language in ('java-kotlin', 'javascript-typescript', 'python')]}).encode()
        else:
            raise AssertionError(args)
    elif args[:2] == ['release', 'create']:
        state['releases'] = [{'id': 1, 'tag_name': args[2], 'draft': True, 'prerelease': False,
            'body': pathlib.Path(args[args.index('--notes-file') + 1]).read_text()}]
    elif args[:2] == ['release', 'upload']:
        path = pathlib.Path(args[3])
        if state.get('fail') == 'upload' and path.name == 'release.json':
            failed = True
        else:
            assert not any(item['name'] == path.name for item in state['assets'])
            state['assets'].append({'id': len(state['assets']) + 1, 'name': path.name,
                'state': 'uploaded', 'data': base64.b64encode(path.read_bytes()).decode()})
    elif args[:2] == ['release', 'edit']:
        if state.get('fail') == 'publish':
            failed = True
        else:
            state['releases'][0]['draft'] = False
    else:
        raise AssertionError(args)
elif name == 'bash':
    if args[0].endswith('select-jar.sh'):
        output = b'target/test.jar\n'
    elif args[0].endswith('smoke-docker.sh'):
        if state.get('fail') == 'smoke':
            failed = True
    else:
        raise AssertionError(args)
elif name == 'skopeo':
    if args[:2] == ['inspect', '--raw']:
        output = raw
    elif args[0] == 'list-tags':
        output = json.dumps({'Tags': list(state['tags'])}).encode()
    elif args[0] == 'copy':
        destination = args[-1]
        if destination.startswith('oci-archive:'):
            pathlib.Path(destination[len('oci-archive:'):]).write_bytes(b'canonical archive')
        elif destination.startswith('docker://'):
            tag = destination.split(':')[-1]
            if state.get('fail') == tag:
                failed = True
            else:
                state['tags'][tag] = base64.b64encode(raw).decode()
    else:
        raise AssertionError(args)
elif name != 'docker':
    raise AssertionError(name)
state_file.write_text(json.dumps(state))
sys.stdout.buffer.write(output)
sys.exit(29 if failed else 0)
'''


class ReleaseCommandIntegrationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.original = Path.cwd()
        os.chdir(self.directory)
        self.addCleanup(os.chdir, self.original)
        (self.directory / 'target').mkdir()
        (self.directory / 'target/test.jar').write_bytes(b'tested JAR')
        binary = self.directory / 'bin'
        binary.mkdir()
        for name in ('gh', 'skopeo', 'docker', 'bash'):
            path = binary / name
            path.write_text(PROGRAM)
            path.chmod(0o755)
        self.state_file = self.directory / 'remote.json'
        self.write({'calls': [], 'releases': [], 'assets': [], 'tags': {}})
        environment = {'PATH': str(binary) + os.pathsep + os.environ['PATH'],
                       'FIXTURE_STATE': str(self.state_file), 'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '1'}
        self.environment = patch.dict(os.environ, environment)
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.http = patch.object(release.urllib.request, 'urlopen', side_effect=self.urlopen)
        self.http.start()
        self.addCleanup(self.http.stop)

    def read(self):
        return json.loads(self.state_file.read_text())

    def write(self, state):
        self.state_file.write_text(json.dumps(state))

    def urlopen(self, request, **kwargs):
        if isinstance(request, str):
            return io.BytesIO(b'{"token":"fixture"}')
        tag = request.full_url.split('/manifests/')[-1]
        data = self.read()['tags'].get(tag)
        if data is None:
            raise urllib.error.HTTPError(request.full_url, 404, '', {}, io.BytesIO(
                b'{"errors":[{"code":"MANIFEST_UNKNOWN"}]}'))
        return io.BytesIO(base64.b64decode(data))

    def run_release(self):
        directory = self.directory / 'work'
        directory.mkdir(exist_ok=True)
        return release.coordinate(release.Services('owner/repo', directory), '1.2.3', SHA, directory)

    def test_command_happy_path_and_same_content_retry_do_not_duplicate_assets(self):
        record = self.run_release()
        self.assertEqual(record['image_digest'], DIGEST)
        state = self.read()
        self.assertFalse(state['releases'][0]['draft'])
        self.assertEqual(set(state['tags']), {'1.2.3', 'latest', '1.2'})
        self.assertEqual(len(state['assets']), 3)
        state['calls'] = []
        self.write(state)
        (self.directory / 'target/test.jar').write_bytes(b'rebuilt, different JAR')
        self.run_release()
        calls = self.read()['calls']
        self.assertFalse(any(call[:2] == ['docker', 'build'] for call in calls))
        self.assertFalse(any(call[:3] in (['gh', 'release', 'upload'], ['gh', 'release', 'edit']) for call in calls))
        self.assertFalse(any(call[:2] == ['skopeo', 'copy'] and call[-1].startswith('docker://') for call in calls))

    def test_each_remote_partial_failure_can_resume_without_rebuilding(self):
        for failure in ('upload', '1.2.3', 'publish', 'latest', '1.2'):
            with self.subTest(failure=failure):
                self.write({'calls': [], 'releases': [], 'assets': [], 'tags': {}, 'fail': failure})
                with self.assertRaisesRegex(ValueError, 'External command'):
                    self.run_release()
                state = self.read()
                self.assertTrue(state['releases'])
                state['fail'] = None
                state['calls'] = []
                self.write(state)
                self.run_release()
                state = self.read()
                self.assertEqual(len(state['assets']), 3)
                self.assertEqual(set(state['tags']), {'1.2.3', 'latest', '1.2'})
                self.assertFalse(any(call[:2] == ['docker', 'build'] for call in state['calls']))

    def test_tampered_remote_jar_fails_before_any_registry_mutation(self):
        self.run_release()
        state = self.read()
        for asset in state['assets']:
            if asset['name'] == 'application.jar':
                asset['data'] = base64.b64encode(b'changed').decode()
        state['calls'] = []
        self.write(state)
        with self.assertRaisesRegex(ValueError, 'checksum conflict'):
            self.run_release()
        self.assertFalse(any(call[:2] == ['skopeo', 'copy'] for call in self.read()['calls']))
