"""Verify the primary post-push workflow and release safety contract."""

from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[3]


class WorkflowConfigTest(unittest.TestCase):
    def test_primary_and_security_workflows_do_not_duplicate_pr_runs(self):
        for name in ('codeql.yml',):
            with self.subTest(workflow=name):
                workflow = (ROOT / '.github/workflows' / name).read_text()
                events = workflow.split('on:\n', 1)[1].split('\nconcurrency:', 1)[0].split('\npermissions:', 1)[0]
                self.assertIn('  push:\n', events)
                self.assertNotIn('  pull_request:', events)
                self.assertIn('master', events)

    def test_dependency_pr_is_read_only_and_human_pr_has_explicit_gate(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        self.assertIn('  pull_request:\n', workflow)
        self.assertNotIn('pull_request_target', workflow)
        classify = re.search(r'\n  classify:\n(.*?)(?=\n  documents:)', workflow, re.S).group(1)
        self.assertIn("github.event.pull_request.user.login == 'dependabot[bot]'", classify)
        self.assertIn("github.event_name != 'pull_request'", classify)
        self.assertIn('Test CI regression guards', classify)
        gate = re.search(r'\n  quality-gate:\n(.*?)(?=\n  release:)', workflow, re.S).group(1)
        self.assertIn('Explain human PR policy', gate)
        self.assertIn("github.event.pull_request.user.login != 'dependabot[bot]'", gate)
        self.assertIn("github.event_name != 'pull_request'", gate)
        release = workflow.split('\n  release:', 1)[1]
        self.assertIn("github.event_name == 'push'", release)
        self.assertIn("startsWith(github.ref, 'refs/tags/v')", release)
        self.assertNotIn('secrets.', workflow.split('\n  release:', 1)[0])
        self.assertNotIn('contents: write', workflow.split('\n  release:', 1)[0])
        for checkout in re.split(r'uses: actions/checkout@[^\s]+', workflow)[1:]:
            self.assertIn('persist-credentials: false', checkout.split('      - ', 1)[0])

    def test_master_cancellation_and_release_serialization_are_preserved(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        self.assertIn("cancel-in-progress: ${{ !startsWith(github.ref, 'refs/tags/') }}", workflow)
        self.assertIn('group: sambryx-release-mutation\n      cancel-in-progress: false', workflow)
        self.assertIn('needs: [quality-gate, build-and-test]', workflow)
        self.assertIn("startsWith(github.ref, 'refs/tags/v')", workflow)
        self.assertIn('run: python3 scripts/ci/release/release.py publish', workflow)

    def test_java_smoke_jobs_prepare_jdk21_and_cache_the_standalone_project(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        for job in ('production-compose-smoke', 'release'):
            block = re.search(r'\n  ' + job + r':\n(.*?)(?=\n  [a-z][a-z-]*:\n|\Z)', workflow, re.S).group(1)
            with self.subTest(job=job):
                self.assertRegex(block, r'uses: actions/setup-java@[^\s]+')
                self.assertIn("java-version: '21'", block)
                self.assertIn('cache-dependency-path: tools/smoke/pom.xml', block)
        self.assertNotIn('smoke-docker.sh', workflow)

    def test_production_smoke_uses_the_java_profile(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        self.assertIn('-Pproduction-smoke verify', workflow)
        self.assertNotIn('smoke-production.py', workflow)

    def test_production_smoke_checks_backup_over_non_root_ssh(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        block = re.search(r'\n  production-compose-smoke:\n(.*?)(?=\n  [a-z][a-z-]*:\n|\Z)', workflow, re.S).group(1)
        self.assertIn('-Dtest=BackupSshIT', block)
        self.assertIn('-Dbackup.ssh.image=sambryx-samba-node:ubuntu24.04', block)

    def test_browser_jobs_run_java_playwright_and_publish_java_diagnostics(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        self.assertIn('-Dit.test=WorkspaceIT,AdministratorSessionIT,ConfigurationBackupIT,BackupCreationIT,ShareAccessIT', workflow)
        self.assertIn('-Dit.test=BrandingIT', workflow)
        self.assertIn('tools/browser/target/browser-artifacts/', workflow)
        self.assertNotIn('npx playwright', workflow)
        self.assertNotIn('node frontend/tests/branding.mjs', workflow)

    def test_browser_job_includes_every_non_branding_integration_class(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        block = re.search(r'\n  browser-smoke:\n(.*?)(?=\n  [a-z][a-z-]*:\n|\Z)', workflow, re.S).group(1)
        selected = set(re.search(r'-Dit.test=([A-Za-z0-9_,]+)', block).group(1).split(','))
        expected = {path.stem for path in (ROOT / 'tools/browser/src/test/java').rglob('*IT.java')}
        expected.discard('BrandingIT')
        self.assertEqual(selected, expected)

    def test_backend_publishes_allure_after_failure_without_repeating_tests(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        block = re.search(r'\n  backend:\n(.*?)(?=\n  [a-z][a-z-]*:\n|\Z)', workflow, re.S).group(1)
        self.assertEqual(block.count('spotless:check verify'), 1)
        self.assertNotIn('continue-on-error', block)
        steps = block.split('      - ')
        report = next((step for step in steps if 'run: bash ./mvnw -B allure:report' in step), '')
        self.assertTrue(report, 'Backend job must generate the Allure HTML report')
        self.assertIn("if: ${{ !cancelled() && hashFiles('target/allure-results/*-result.json') != '' }}", report)
        uploads = [step for step in steps if re.search(r'uses: actions/upload-artifact@[^\s]+', step)]
        for name, path, pattern in (
                ('backend-allure-results', 'target/allure-results/', 'target/allure-results/*-result.json'),
                ('backend-allure-report', 'target/site/allure-maven-plugin/',
                 'target/site/allure-maven-plugin/index.html')):
            upload = next((step for step in uploads if 'name: ' + name + '\n' in step), '')
            with self.subTest(artifact=name):
                self.assertTrue(upload, 'Allure artifact must be downloadable')
                self.assertIn("if: ${{ !cancelled() && hashFiles('" + pattern + "') != '' }}", upload)
                self.assertIn('path: ' + path, upload)
                self.assertIn('retention-days: 7', upload)
                self.assertIn('if-no-files-found: error', upload)
