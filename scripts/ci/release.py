#!/usr/bin/env python3
"""Coordinate stable Sambryx releases and monotonic alias repair."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request

STABLE = re.compile(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)')

IMAGE = 'andreyvorobevaqa/sambryx'
DIGEST = re.compile(r'sha256:[a-f0-9]{64}')
HEX = re.compile(r'[a-f0-9]{64}')
ASSETS = ('image.oci.tar', 'application.jar', 'release.json')
BODY_PREFIX = 'Release recovery record (do not edit):\n```json\n'


def semver(value):
    if not STABLE.fullmatch(value):
        raise ValueError('Expected a canonical stable version X.Y.Z')
    return tuple(map(int, value.split('.')))


def coordinate(boundary, version, sha, directory, repair=False):
    target = semver(version)
    boundary.check_codeql(sha)
    if repair and version not in boundary.versions():
        raise ValueError('Repair requires an existing public stable release')
    record = boundary.load(version, sha, directory)
    if record is None:
        if repair:
            raise ValueError('Repair requires an existing release record')
        # Preflight registry access before preparing or writing any recovery assets.
        if boundary.digest(version) is not None:
            raise ValueError('Version conflict: image exists without a recovery record')
        record = boundary.prepare(version, sha, directory)
        boundary.persist(record, directory)
    boundary.verify(record, directory)
    digest = record['image_digest']
    actual = boundary.digest(version)
    if actual is not None and actual != digest:
        raise ValueError('Version conflict: published digest differs from recovery record')
    if actual is None:
        if repair:
            raise ValueError('Repair requires an existing version image')
        boundary.copy_version(record, directory)
    if boundary.digest(version) != digest:
        raise ValueError('Published version digest mismatch')
    boundary.verify_published(record, directory)
    if not repair:
        boundary.publish(record)
    published = boundary.versions()
    if version not in published:
        raise ValueError('Aliases require a public stable GitHub Release')
    known = boundary.tag_digests()
    aliases = ('latest', '.'.join(version.split('.')[:2]))
    for alias in aliases:
        relevant = [v for v in published if STABLE.fullmatch(v) and
                    (alias == 'latest' or semver(v)[:2] == target[:2])]
        newest = max(relevant, key=semver)
        current = boundary.digest(alias)
        identities = [semver(v) for v, d in known.items()
                      if STABLE.fullmatch(v) and d == current and
                      (alias == 'latest' or semver(v)[:2] == target[:2])]
        if current is not None and not identities:
            raise ValueError('Unknown alias identity: ' + alias)
        if semver(newest) > target:
            if current is None or current != known.get(newest):
                raise ValueError('Superseded alias requires repair using version ' + newest + ': ' + alias)
            continue
        if identities and max(identities) > target:
            raise ValueError('Newer unpublished alias identity must not be rolled back: ' + alias)
        if current != digest:
            boundary.copy_alias(alias, digest)
        if boundary.digest(alias) != digest:
            raise ValueError('Published alias digest mismatch: ' + alias)
    if boundary.digest(version) != digest:
        raise ValueError('Version digest changed during alias publication')
    return record


def encoded(record):
    return (json.dumps(record, sort_keys=True, indent=2) + '\n').encode()


def record_body(record):
    return BODY_PREFIX + encoded(record).decode() + '```\n'


def checksum(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def validate_record(record, version, sha):
    if record.get('schema') != 1 or record.get('version') != version or record.get('source_sha') != sha:
        raise ValueError('Recovery record version/source conflict')
    if record.get('image') != IMAGE or not DIGEST.fullmatch(record.get('image_digest', '')):
        raise ValueError('Invalid recovery image identity')
    if any(not HEX.fullmatch(record.get(key, '')) for key in ('jar_sha256', 'archive_sha256')):
        raise ValueError('Invalid recovery asset checksum')


class Services:
    def __init__(self, repository, directory):
        if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
            raise ValueError('Invalid repository')
        self.repository = repository
        self.directory = directory
        self.registry_token = None
        self.remote = None
        self.observed = {}

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

    def assets(self, remote):
        return [item for page in self.api('releases/' + str(remote['id']) + '/assets?per_page=100', pages=True)
                for item in page]

    def download(self, asset):
        return self.command('gh', 'api', 'repos/' + self.repository + '/releases/assets/' + str(asset['id']),
                            '-H', 'Accept: application/octet-stream')

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
                self.observed['codeql_run'] = run['id']
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
                except (ValueError, AttributeError):
                    pass
            error.close()
            raise ValueError('Registry request failed with HTTP ' + str(error.code)) from error
        except (OSError, TimeoutError, ValueError, KeyError) as error:
            raise ValueError('Registry request failed; absence was not established') from error

    def digest(self, tag):
        raw = self.registry_request('/manifests/' + urllib.parse.quote(tag, safe=':'))
        digest = None if raw is None else 'sha256:' + hashlib.sha256(raw).hexdigest()
        self.observed[tag] = digest
        return digest

    def tag_digests(self):
        tags = json.loads(self.command('skopeo', 'list-tags', 'docker://' + IMAGE))['Tags']
        identities = {tag: self.digest(tag) for tag in tags if STABLE.fullmatch(tag)}
        if any(digest is None for digest in identities.values()):
            raise ValueError('Registry tag inventory changed or is incomplete')
        return identities

    def versions(self):
        return [item['tag_name'][1:] for item in self.releases()
                if not item['draft'] and not item['prerelease'] and item['tag_name'].startswith('v')
                and STABLE.fullmatch(item['tag_name'][1:])]

    def load(self, version, sha, directory):
        matches = [item for item in self.releases() if item['tag_name'] == 'v' + version]
        if not matches:
            return None
        if len(matches) != 1:
            raise ValueError('Duplicate release conflict')
        self.remote = matches[0]
        body = self.remote.get('body', '') or ''
        if not body.startswith(BODY_PREFIX) or '\n```' not in body[len(BODY_PREFIX):]:
            raise ValueError('Existing release has no canonical recovery record; manual investigation required')
        record = json.loads(body[len(BODY_PREFIX):].split('\n```', 1)[0])
        validate_record(record, version, sha)
        (directory / 'release.json').write_bytes(encoded(record))
        assets = self.assets(self.remote)
        if len({asset['name'] for asset in assets}) != len(assets) or any(asset['name'] not in ASSETS for asset in assets):
            raise ValueError('Unexpected or duplicate release asset conflict')
        by_name = {asset['name']: asset for asset in assets}
        if self.remote['draft'] and any(name not in by_name and not (directory / name).exists() for name in ASSETS):
            self.restore(record, directory)
        for name in ASSETS:
            destination = directory / name
            if name in by_name:
                if by_name[name].get('state') != 'uploaded':
                    raise ValueError('Incomplete asset conflict: ' + name + '; inspect the original run before recovery')
                data = self.download(by_name[name])
                if name == 'release.json' and data != encoded(record):
                    raise ValueError('Recovery metadata asset conflict')
                destination.write_bytes(data)
            elif not destination.exists():
                raise ValueError('Missing canonical asset: ' + name + '; recover exact bytes from the original run artifact')
        self.verify_files(record, directory)
        if set(by_name) != set(ASSETS):
            if not self.remote['draft']:
                raise ValueError('Published release is missing canonical assets')
            self.persist(record, directory)
        return record

    def restore(self, record, directory):
        run_id, attempt = record.get('run_id', ''), record.get('run_attempt', '')
        if not re.fullmatch(r'[1-9][0-9]*', run_id) or not re.fullmatch(r'[1-9][0-9]*', attempt):
            raise ValueError('Missing canonical assets and valid original run identity')
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary)
            self.command('gh', 'run', 'download', run_id, '--repo', self.repository,
                         '--name', 'release-recovery-' + run_id + '-' + attempt, '--dir', str(destination))
            if (destination / 'release.json').read_bytes() != encoded(record):
                raise ValueError('Original run artifact metadata conflict')
            self.verify_files(record, destination)
            for name in ASSETS:
                if not (directory / name).exists():
                    shutil.copyfile(destination / name, directory / name)

    def prepare(self, version, sha, directory):
        jar = self.command('bash', 'scripts/ci/select-jar.sh', 'target').decode().strip()
        shutil.copyfile(jar, directory / 'application.jar')
        self.observed['jar_sha256'] = checksum(directory / 'application.jar')
        self.command('docker', 'build', '--label', 'org.opencontainers.image.version=' + version,
                     '--label', 'org.opencontainers.image.revision=' + sha,
                     '--label', 'org.opencontainers.image.source=https://github.com/' + self.repository,
                     '--build-arg', 'JAR_FILE=' + jar, '--tag', 'sambryx-release:prepared', '.')
        self.command('bash', 'scripts/ci/smoke-docker.sh', 'sambryx-release:prepared', str(directory / 'application.jar'))
        archive = directory / 'image.oci.tar'
        self.command('skopeo', 'copy', 'docker-daemon:sambryx-release:prepared', 'oci-archive:' + str(archive))
        raw = self.command('skopeo', 'inspect', '--raw', 'oci-archive:' + str(archive))
        record = {'schema': 1, 'version': version, 'source_sha': sha, 'image': IMAGE,
                  'image_digest': 'sha256:' + hashlib.sha256(raw).hexdigest(),
                  'jar_sha256': checksum(directory / 'application.jar'), 'archive_sha256': checksum(archive),
                  'run_id': os.environ['GITHUB_RUN_ID'], 'run_attempt': os.environ['GITHUB_RUN_ATTEMPT']}
        (directory / 'release.json').write_bytes(encoded(record))
        return record

    def persist(self, record, directory):
        self.verify_files(record, directory)
        if self.remote is None:
            notes = directory / 'notes.md'
            generated = json.loads(self.command('gh', 'api', 'repos/' + self.repository + '/releases/generate-notes',
                                                '-f', 'tag_name=v' + record['version'],
                                                '-f', 'target_commitish=' + record['source_sha']))['body']
            notes.write_text(record_body(record) + '\n' + generated, encoding='utf-8')
            self.command('gh', 'release', 'create', 'v' + record['version'], '--repo', self.repository,
                         '--verify-tag', '--draft', '--target', record['source_sha'],
                         '--title', 'Release v' + record['version'], '--notes-file', str(notes))
            matches = [item for item in self.releases() if item['tag_name'] == 'v' + record['version']]
            if len(matches) != 1:
                raise ValueError('Cannot verify prepared draft release')
            self.remote = matches[0]
        existing = {item['name']: item for item in self.assets(self.remote)}
        for name in ASSETS:
            path = directory / name
            if name in existing:
                if existing[name].get('state') != 'uploaded' or self.download(existing[name]) != path.read_bytes():
                    raise ValueError('Existing asset conflict: ' + name)
            else:
                self.command('gh', 'release', 'upload', 'v' + record['version'], str(path), '--repo', self.repository)
        self.observed['stage'] = 'prepared draft and canonical assets persisted'

    def verify_files(self, record, directory):
        for name, key in (('application.jar', 'jar_sha256'), ('image.oci.tar', 'archive_sha256')):
            if not (directory / name).is_file() or checksum(directory / name) != record[key]:
                raise ValueError('Canonical asset checksum conflict: ' + name)
        if (directory / 'release.json').read_bytes() != encoded(record):
            raise ValueError('Recovery metadata conflict')

    def verify(self, record, directory):
        self.verify_files(record, directory)
        archive = 'oci-archive:' + str(directory / 'image.oci.tar')
        raw = self.command('skopeo', 'inspect', '--raw', archive)
        if 'sha256:' + hashlib.sha256(raw).hexdigest() != record['image_digest']:
            raise ValueError('Archive manifest digest conflict')
        self.command('skopeo', 'copy', archive, 'docker-daemon:sambryx-release:verified')
        self.command('bash', 'scripts/ci/smoke-docker.sh', 'sambryx-release:verified', str(directory / 'application.jar'))

    def copy_version(self, record, directory):
        self.command('skopeo', 'copy', '--all', '--preserve-digests',
                     'oci-archive:' + str(directory / 'image.oci.tar'), 'docker://' + IMAGE + ':' + record['version'])
        self.observed['stage'] = 'version image copied; verification pending'

    def verify_published(self, record, directory):
        reference = IMAGE + '@' + record['image_digest']
        self.command('docker', 'pull', reference)
        self.command('bash', 'scripts/ci/smoke-docker.sh', reference, str(directory / 'application.jar'))
        self.observed['stage'] = 'version digest and pulled image smoke verified'

    def publish(self, record):
        # Re-read the remote assets before making the release public, including on retry.
        self.load(record['version'], record['source_sha'], self.directory)
        if self.remote['draft']:
            previous = self.versions()
            latest = not any(semver(version) > semver(record['version']) for version in previous)
            self.command('gh', 'release', 'edit', 'v' + record['version'], '--repo', self.repository,
                         '--draft=false', '--latest=' + str(latest).lower())
        if record['version'] not in self.versions():
            raise ValueError('GitHub Release publication verification failed')
        self.observed['stage'] = 'public GitHub Release verified'

    def copy_alias(self, alias, digest):
        self.command('skopeo', 'copy', '--all', '--preserve-digests',
                     'docker://' + IMAGE + '@' + digest, 'docker://' + IMAGE + ':' + alias)
        self.observed['stage'] = 'alias copied: ' + alias + '; verification pending'

def invocation(environment, operation, version):
    if environment.get('GITHUB_ACTIONS') != 'true' or environment.get('GITHUB_REPOSITORY') != 'AndreyJVM/sambryx':
        raise ValueError('Mutation requires the authorized repository Actions environment')
    if operation == 'publish':
        if environment.get('GITHUB_EVENT_NAME') != 'push' or not environment.get('GITHUB_REF', '').startswith('refs/tags/v'):
            raise ValueError('Publication requires a stable tag push')
        needs = json.loads(environment.get('RELEASE_NEEDS', '{}'))
        if any(needs.get(job, {}).get('result') != 'success' for job in ('quality-gate', 'build-and-test')):
            raise ValueError('Publication requires successful quality-gate and build-and-test dependencies')
        version = environment['GITHUB_REF'][len('refs/tags/v'):]
    elif operation == 'repair':
        if environment.get('GITHUB_EVENT_NAME') != 'workflow_dispatch' or environment.get('GITHUB_REF') != 'refs/heads/master':
            raise ValueError('Repair requires manual dispatch from master')
    else:
        raise ValueError('Unknown release operation')
    semver(version or '')
    return version


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('publish', 'repair'))
    parser.add_argument('--version')
    arguments = parser.parse_args()
    services = None
    directory = Path('release-work').resolve()
    status = 'failed'
    failure = None
    try:
        version = invocation(os.environ, arguments.operation, arguments.version)
        directory.mkdir(exist_ok=True)
        services = Services(os.environ['GITHUB_REPOSITORY'], directory)
        sha = services.api('commits/v' + version)['sha']
        services.observed.update({'source_sha': sha, 'version': version})
        if arguments.operation == 'publish':
            head = services.command('git', 'rev-parse', 'HEAD').decode().strip()
            if head != sha or os.environ.get('GITHUB_SHA') != sha:
                raise ValueError('Checked-out source and current tag SHA differ')
        coordinate(services, version, sha, directory, repair=arguments.operation == 'repair')
        services.observed['stage'] = 'complete; superseded aliases were preserved'
        status = 'success'
        return 0
    except (ValueError, KeyError, TypeError, OSError) as error:
        failure = str(error)
        print('Release stopped: ' + failure, file=sys.stderr)
        return 1
    finally:
        if services is not None:
            report = {'status': status, 'observed': services.observed}
            if failure is not None:
                report['failure'] = failure
            manifest = directory / 'release.json'
            if manifest.exists():
                try:
                    report['record'] = json.loads(manifest.read_bytes())
                except ValueError:
                    report['record'] = 'Invalid local recovery record'
            summary = ('## Stable release state\n\n```json\n' + json.dumps(report, indent=2, sort_keys=True)
                       + '\n```\n\nRetry the original tag workflow after resolving the reported failure. '
                       'For missing canonical bytes, download the original release-recovery Actions artifact '
                       'and inspect docs/distribution.md before recovery. Never replace conflicting assets/tags. '
                       'After a public release, manual Repair Docker Release Aliases can finish aliases; '
                       'use the newest version for each alias line.\n')
            print(summary)
            if os.environ.get('GITHUB_STEP_SUMMARY'):
                with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as output:
                    output.write(summary)


if __name__ == '__main__':
    sys.exit(main())
