"""Behavioral contracts for CI selection and fail-closed aggregation."""
import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("scope", Path(__file__).with_name("scope.py"))
scope = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scope)


class SelectionTest(unittest.TestCase):
    def test_readme_requires_only_document_validation(self):
        self.assertEqual(scope.select(["README.md"]), {"documents"})

    def test_documentation_requires_strict_build(self):
        self.assertEqual(scope.select(["docs/testing.md"]), {"documents", "mkdocs"})

    def test_branding_requires_assets_and_consumed_frontend(self):
        self.assertEqual(scope.select(["frontend/public/sambryx/logo-mark.svg"]),
                         {"documents", "assets", "frontend", "package", "browser"})

    def test_frontend_and_backend_preserve_packaging(self):
        self.assertEqual(scope.select(["frontend/src/App.tsx"]), {"frontend", "package", "browser"})
        self.assertEqual(scope.select(["src/main/java/example/App.java"]), {"backend", "package"})

    def test_sensitive_unknown_and_empty_are_full(self):
        for files in ([], ["pom.xml"], ["frontend/package-lock.json"], [".github/workflows/ci.yml"],
                      ["scripts/helper.sh"], ["Dockerfile"], ["new-area/file"],
                      ["frontend/public/worker.js"], ["frontend/tests/branding.mjs"], ["src/main/resources/application.yml"],
                      ["src/main/java/mari/samba/infra/LinuxCommands.java"],
                      ["src/main/java/mari/samba/config/SecurityConfig.java"],
                      ["src/main/java/mari/samba/auth/AuthServiceImpl.java"],
                      ["src/main/java/mari/samba/session/AdministratorSessionFilter.java"],
                      ["src/main/java/mari/samba/smbconfig/SambaConfigService.java"]):
            with self.subTest(files=files):
                self.assertEqual(scope.select(files), scope.FULL)

    def test_union_and_rename_include_both_sides(self):
        self.assertEqual(scope.select(["README.md", "src/test/java/example/Test.java"]),
                         {"documents", "backend", "package"})
        self.assertEqual(scope.parse_diff(b"R100\0README.md\0scripts/run.sh\0"),
                         ["README.md", "scripts/run.sh"])
        self.assertEqual(scope.parse_diff(b"D\0docs/old.md\0A\0README.md\0"),
                         ["docs/old.md", "README.md"])

    def test_malformed_diff_is_rejected(self):
        for data in (b"M\0README.md", b"R100\0README.md\0", b"X\0README.md\0", b"M\0\xff\0", b"T\0README.md\0", b"U\0README.md\0"):
            with self.subTest(data=data), self.assertRaises(ValueError):
                scope.parse_diff(data)


class GateTest(unittest.TestCase):
    def test_only_explicit_unselected_skips_are_accepted(self):
        plan = scope.plan({"documents"})
        needs = {key: {"result": "success" if expected else "skipped"}
                 for key, expected in plan.items()}
        needs["classify"] = {"result": "success"}
        self.assertTrue(scope.gate(plan, needs))
        for job in plan:
            for result in ("failure", "cancelled", "skipped") if plan[job] else ("failure", "cancelled", "success"):
                with self.subTest(job=job, result=result):
                    changed = {**needs, job: {"result": result}}
                    self.assertFalse(scope.gate(plan, changed))
        for result in ("failure", "cancelled", "skipped", ""):
            self.assertFalse(scope.gate(plan, {**needs, "classify": {"result": result}}))

    def test_missing_extra_and_invalid_plan_fail_closed(self):
        self.assertFalse(scope.gate({}, {}))
        empty = scope.plan(set())
        skipped = {job: {"result": "skipped"} for job in empty}
        self.assertFalse(scope.gate(empty, {**skipped, "classify": {"result": "success"}}))
        self.assertFalse(scope.gate(scope.plan(scope.FULL), {}))
        self.assertFalse(scope.gate({**scope.plan(scope.FULL), "unknown": True}, {}))


class RangeTest(unittest.TestCase):
    def setUp(self):
        import os
        import subprocess
        import tempfile
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.previous = os.getcwd()
        os.chdir(self.temp.name)
        self.addCleanup(os.chdir, self.previous)
        subprocess.run(["git", "init", "-q"], check=True)
        self.git("config", "user.email", "ci@example.invalid")
        self.git("config", "user.name", "CI Test")
        self.commit("README.md")
        self.base = self.git("rev-parse", "HEAD")

    def git(self, *args):
        return scope.git(*args).decode().strip()

    def commit(self, path):
        file = Path(path)
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text("fixture\n")
        self.git("add", "--", path)
        self.git("commit", "-qm", "Fixture")

    def test_entire_pr_uses_merge_base_across_multiple_commits(self):
        self.git("checkout", "-qb", "feature")
        self.commit("src/main/java/First.java")
        self.commit("docs/second.md")
        head = self.git("rev-parse", "HEAD")
        self.git("checkout", "-q", "master")
        self.commit("scripts/base-only.sh")
        base = self.git("rev-parse", "HEAD")
        event = {"pull_request": {"base": {"sha": base}, "head": {"sha": head}}}
        self.assertEqual(set(scope.changes(event, "pull_request", "refs/pull/1/merge")),
                         {"src/main/java/First.java", "docs/second.md"})
        self.git("checkout", "-q", "feature")
        self.git("merge", "--no-edit", "master")
        event["pull_request"]["head"]["sha"] = self.git("rev-parse", "HEAD")
        self.assertEqual(set(scope.changes(event, "pull_request", "refs/pull/1/merge")),
                         {"src/main/java/First.java", "docs/second.md"})

    def test_push_includes_all_commits_and_deleted_rename_source(self):
        self.commit("docs/second.md")
        self.git("mv", "README.md", "renamed.md")
        self.git("commit", "-qm", "Rename")
        event = {"before": self.base, "after": self.git("rev-parse", "HEAD")}
        self.assertEqual(set(scope.changes(event, "push", "refs/heads/master")),
                         {"README.md", "renamed.md", "docs/second.md"})

    def test_empty_zero_missing_manual_and_tag_ranges(self):
        event = {"before": self.base, "after": self.base}
        self.assertEqual(scope.select(scope.changes(event, "push", "refs/heads/master")), scope.FULL)
        for name, ref, before in (("workflow_dispatch", "refs/heads/master", self.base),
                                  ("push", "refs/tags/v1.2.3", self.base),
                                  ("push", "refs/heads/master", "0" * 40),
                                  ("push", "refs/heads/master", "f" * 40)):
            event["before"] = before
            with self.subTest(name=name, ref=ref, before=before), self.assertRaises((ValueError, __import__("subprocess").CalledProcessError)):
                scope.changes(event, name, ref)

    def test_shallow_history_is_not_trusted(self):
        import os
        import tempfile
        original = os.getcwd()
        with tempfile.TemporaryDirectory() as directory:
            scope.git("clone", "--depth=1", "file://" + original, directory)
            try:
                os.chdir(directory)
                with self.assertRaises(ValueError):
                    scope.changes({"before": self.base, "after": self.base}, "push", "refs/heads/master")
            finally:
                os.chdir(original)

    def test_paths_with_spaces_newlines_and_shell_text_remain_data(self):
        path = "docs/space\n$(touch sentinel).md"
        self.commit(path)
        event = {"before": self.base, "after": self.git("rev-parse", "HEAD")}
        self.assertEqual(scope.changes(event, "push", "refs/heads/master"), [path])
        self.assertFalse(Path("sentinel").exists())


class CommandTest(unittest.TestCase):
    def test_manual_tag_and_unavailable_diff_emit_full_plan(self):
        import json
        import os
        import subprocess
        import tempfile
        for name, ref, event in (("workflow_dispatch", "refs/heads/master", {}),
                                  ("push", "refs/tags/v1.2.3", {}),
                                  ("pull_request", "refs/pull/1/merge", {"pull_request": None}),
                                  ("push", "refs/heads/master", {"before": "0" * 40, "after": "f" * 40})):
            with self.subTest(name=name, ref=ref), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / "event.json").write_text(json.dumps(event))
                output = root / "output"
                result = subprocess.run(["python3", str(Path(scope.__file__)), "classify"],
                    env={**os.environ, "GITHUB_EVENT_PATH": str(root / "event.json"),
                         "GITHUB_EVENT_NAME": name, "GITHUB_REF": ref, "GITHUB_OUTPUT": str(output)},
                    capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                outputs = dict(line.split("=", 1) for line in output.read_text().splitlines())
                self.assertEqual(json.loads(outputs["plan"]), scope.plan(scope.FULL))
                self.assertTrue(all(outputs[area] == "true" for area in scope.FULL))

    def test_gate_process_rejects_malformed_missing_and_failed_dependencies(self):
        import json
        import os
        import subprocess
        expected = scope.plan(scope.FULL)
        needs = {job: {"result": "success"} for job in [*expected, "classify"]}
        for plan, results, code in ((json.dumps(expected), json.dumps(needs), 0),
                                    ("", json.dumps(needs), 1), ("null", "[]", 1),
                                    (json.dumps(expected), "{}", 1),
                                    (json.dumps(expected), json.dumps({**needs, "backend": {"result": "failure"}}), 1)):
            with self.subTest(plan=plan, results=results):
                result = subprocess.run(["python3", str(Path(scope.__file__)), "gate"],
                    env={**os.environ, "EXPECTED_PLAN": plan, "JOB_RESULTS": results},
                    capture_output=True, text=True)
                self.assertEqual(result.returncode, code, result.stderr)


if __name__ == "__main__":
    unittest.main()
