#!/usr/bin/env python3
"""Publish a new immutable version; stop for manual investigation on partial failure."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

IMAGE = 'andreyvorobevaqa/sambryx'
STABLE = re.compile(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)')


def semver(value):
    if not STABLE.fullmatch(value):
        raise ValueError('Expected a canonical stable version X.Y.Z')
    return tuple(map(int, value.split('.')))


class Services:
    def __init__(self, repository):
        if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
            raise ValueError('Invalid repository')
        self.repository = repository
        self.registry_token = None

    def command(self, *args):
        try:
            return subprocess.run(args, check=True, stdout=subprocess.PIPE,
                                  stderr=subprocess.PIPE, timeout=1200).stdout
        except (subprocess.SubprocessError, OSError) as error:
            raise ValueError('External command failed: ' + ' '.join(args[:3])) from error

    def api(self, path, pages=False):
        args = ['gh', 'api', 'repos/' + self.repository + '/' + path]
        if pages:
            args += ['--paginate']
        raw = self.command(*args).decode()
        if not pages:
            return json.loads(raw)
        decoder = json.JSONDecoder()
        result = []
        while raw.strip():
            value, end = decoder.raw_decode(raw.lstrip())
            result.append(value)
            raw = raw.lstrip()[end:]
        if not result:
            raise ValueError('GitHub API returned no pagination result')
        return result

    def releases(self):
        return [item for page in self.api('releases?per_page=100', pages=True) for item in page]

    def check_codeql(self, sha):
        if not re.fullmatch(r'[a-f0-9]{40}', sha):
            raise ValueError('Invalid source SHA')
        pages = self.api('actions/workflows/codeql.yml/runs?head_sha=' + sha + '&event=push&per_page=100', pages=True)
        runs = [run for page in pages for run in page['workflow_runs']
                if run.get('head_sha') == sha and run.get('event') == 'push'
                and run.get('head_branch') in ('master', 'main') and run.get('conclusion') == 'success'
                and run.get('head_repository', {}).get('full_name') == self.repository]
        required = {'Analyze (' + language + ')' for language in ('java-kotlin', 'javascript-typescript', 'python')}
        for run in runs:
            pages = self.api('actions/runs/' + str(run['id']) + '/jobs?per_page=100', pages=True)
            jobs = [job for page in pages for job in page['jobs']]
            if {job['name'] for job in jobs} == required and all(job.get('conclusion') == 'success' for job in jobs):
                return
        raise ValueError('CodeQL requires a successful master/main push analysis of the exact source SHA; rerun CodeQL first')

    def registry_request(self, suffix):
        try:
            if self.registry_token is None:
                url = 'https://auth.docker.io/token?' + urllib.parse.urlencode({
                    'service': 'registry.docker.io', 'scope': 'repository:' + IMAGE + ':pull'})
                with urllib.request.urlopen(url, timeout=30) as response:
                    self.registry_token = json.load(response)['token']
            request = urllib.request.Request('https://registry-1.docker.io/v2/' + IMAGE + suffix, headers={
                'Authorization': 'Bearer ' + self.registry_token,
                'Accept': ', '.join(('application/vnd.oci.image.manifest.v1+json',
                                     'application/vnd.docker.distribution.manifest.v2+json',
                                     'application/vnd.oci.image.index.v1+json',
                                     'application/vnd.docker.distribution.manifest.list.v2+json'))})
            with urllib.request.urlopen(request, timeout=30) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            # Only a structured missing manifest response is absence. Auth/token errors never are.
            if error.code == 404 and self.registry_token is not None and suffix.startswith('/manifests/'):
                try:
                    errors = json.load(error).get('errors', [])
                    if errors and all(item.get('code') == 'MANIFEST_UNKNOWN' for item in errors):
                        error.close()
                        return None
                except (ValueError, AttributeError) as malformed:
                    error.close()
                    raise ValueError('Registry response was not a valid absence proof') from malformed
            error.close()
            raise ValueError('Registry request failed with HTTP ' + str(error.code)) from error
        except (OSError, TimeoutError, ValueError, KeyError) as error:
            raise ValueError('Registry request failed; absence was not established') from error

    def digest(self, tag):
        raw = self.registry_request('/manifests/' + urllib.parse.quote(tag, safe=':'))
        digest = None if raw is None else 'sha256:' + hashlib.sha256(raw).hexdigest()
        return digest


def publish(services, version, sha, directory):
    target = semver(version)
    services.check_codeql(sha)
    previous = services.releases()
    if any(item['tag_name'] == 'v' + version for item in previous):
        raise ValueError('Release already exists; investigate manually, do not overwrite')
    if services.digest(version) is not None:
        raise ValueError('Image already exists; investigate manually, do not overwrite')

    directory.mkdir(exist_ok=True)
    jar = services.command('bash', 'scripts/ci/release/select-jar.sh', 'target').decode().strip()
    application = directory / 'application.jar'
    shutil.copyfile(jar, application)
    checksum = hashlib.sha256(application.read_bytes()).hexdigest()
    (directory / 'SHA256SUMS').write_text(checksum + '  application.jar\n', encoding='utf-8')
    reference = IMAGE + ':' + version
    services.command('docker', 'build', '--label', 'org.opencontainers.image.version=' + version,
                     '--label', 'org.opencontainers.image.revision=' + sha,
                     '--label', 'org.opencontainers.image.source=https://github.com/' + services.repository,
                     '--build-arg', 'JAR_FILE=' + jar, '--tag', reference, '.')
    services.command('bash', 'scripts/ci/smoke/smoke-docker.sh', reference, str(application))
    services.command('gh', 'release', 'create', 'v' + version, '--repo', services.repository,
                     '--verify-tag', '--draft', '--target', sha, '--title', 'Release v' + version,
                     '--generate-notes')
    services.command('docker', 'push', reference)
    digest = services.digest(version)
    if not digest or not re.fullmatch(r'sha256:[a-f0-9]{64}', digest):
        raise ValueError('Published image digest is missing or invalid')
    services.command('docker', 'pull', IMAGE + '@' + digest)
    services.command('bash', 'scripts/ci/smoke/smoke-docker.sh', IMAGE + '@' + digest, str(application))
    if services.digest(version) != digest:
        raise ValueError('Version digest changed during publication')
    record = {'version': version, 'source_sha': sha, 'image': IMAGE,
              'image_digest': digest, 'jar_sha256': checksum}
    (directory / 'release.json').write_text(json.dumps(record, indent=2) + '\n', encoding='utf-8')
    services.command('gh', 'release', 'upload', 'v' + version,
                     str(application), str(directory / 'SHA256SUMS'), str(directory / 'release.json'),
                     '--repo', services.repository)
    newer = any(not item['draft'] and not item['prerelease'] and
                item['tag_name'].startswith('v') and STABLE.fullmatch(item['tag_name'][1:]) and
                semver(item['tag_name'][1:]) > target for item in previous)
    services.command('gh', 'release', 'edit', 'v' + version, '--repo', services.repository,
                     '--draft=false', '--latest=' + str(not newer).lower())
    if not any(item['tag_name'] == 'v' + version and not item['draft']
               for item in services.releases()):
        raise ValueError('Public GitHub Release was not confirmed')
    return record


def invocation(environment):
    if environment.get('GITHUB_ACTIONS') != 'true' or environment.get('GITHUB_REPOSITORY') != 'AndreyJVM/sambryx':
        raise ValueError('Publication requires the authorized repository Actions environment')
    if environment.get('GITHUB_EVENT_NAME') != 'push' or not environment.get('GITHUB_REF', '').startswith('refs/tags/v'):
        raise ValueError('Publication requires a stable tag push')
    needs = json.loads(environment.get('RELEASE_NEEDS', '{}'))
    if any(needs.get(job, {}).get('result') != 'success' for job in ('quality-gate', 'build-and-test')):
        raise ValueError('Publication requires successful quality-gate and build-and-test dependencies')
    version = environment['GITHUB_REF'][len('refs/tags/v'):]
    semver(version)
    return version


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('publish',))
    parser.parse_args()
    try:
        version = invocation(os.environ)
        directory = Path('release-work').resolve()
        services = Services(os.environ['GITHUB_REPOSITORY'])
        sha = services.api('commits/v' + version)['sha']
        head = services.command('git', 'rev-parse', 'HEAD').decode().strip()
        if head != sha or os.environ.get('GITHUB_SHA') != sha:
            raise ValueError('Checked-out source and current tag SHA differ')
        record = publish(services, version, sha, directory)
        print(json.dumps(record, indent=2))
        return 0
    except (ValueError, KeyError, TypeError, OSError) as error:
        print('Release stopped: ' + str(error), file=sys.stderr)
        print('Inspect GitHub draft, versioned image and release-files artifact before retrying. '
              'Never overwrite an existing version. See docs/distribution.md#release-recovery.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
