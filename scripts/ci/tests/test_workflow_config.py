"""Verify the primary post-push workflow and release safety contract."""

from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[3]


class WorkflowConfigTest(unittest.TestCase):
    def test_primary_and_security_workflows_do_not_duplicate_pr_runs(self):
        for name in ('ci.yml', 'codeql.yml'):
            with self.subTest(workflow=name):
                workflow = (ROOT / '.github/workflows' / name).read_text()
                events = workflow.split('on:\n', 1)[1].split('\nconcurrency:', 1)[0].split('\npermissions:', 1)[0]
                self.assertIn('  push:\n', events)
                self.assertNotIn('  pull_request:', events)
                self.assertIn('master', events)

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
                self.assertIn('uses: actions/setup-java@v4', block)
                self.assertIn("java-version: '21'", block)
                self.assertIn('cache-dependency-path: tools/smoke/pom.xml', block)
        self.assertNotIn('smoke-docker.sh', workflow)

    def test_production_smoke_uses_the_java_profile(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        self.assertIn('-Pproduction-smoke verify', workflow)
        self.assertNotIn('smoke-production.py', workflow)

    def test_browser_jobs_run_java_playwright_and_publish_java_diagnostics(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        self.assertIn('-Dit.test=BrowserSuiteIT', workflow)
        self.assertIn('-Dit.test=BrandingIT', workflow)
        self.assertIn('tools/browser/target/browser-artifacts/', workflow)
        self.assertNotIn('npx playwright', workflow)
        self.assertNotIn('node frontend/tests/branding.mjs', workflow)
