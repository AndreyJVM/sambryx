"""Verify the primary post-push workflow and release safety contract."""

from pathlib import Path
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
