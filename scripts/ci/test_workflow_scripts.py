"""Exercise artifact selection and publication through subprocess boundaries."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]


class WorkflowScriptsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        (self.directory / "target").mkdir()
        (self.directory / "scripts").symlink_to(ROOT / "scripts", target_is_directory=True)
        self.calls = self.directory / "calls.jsonl"
        binary = self.directory / "bin"
        binary.mkdir()
        docker = binary / "docker"
        docker.write_text("#!/usr/bin/env python3\nimport json, os, sys\n"
                          "with open(os.environ['DOCKER_CALLS'], 'a') as output:\n"
                          "    output.write(json.dumps(sys.argv[1:]) + '\\n')\n"
                          "sys.exit(17 if os.environ.get('FAIL_TAG') in sys.argv[1:] else 0)\n")
        docker.chmod(0o755)
        self.environment = {**os.environ, "PATH": str(binary) + os.pathsep + os.environ["PATH"],
                            "DOCKER_CALLS": str(self.calls),
                            "STANDARD_TAGS": "example/app:1.2.3\nexample/app:latest",
                            "ASTRA_TAGS": "example/app:astra-1.2.3\nexample/app:astra-latest"}

    def jar(self, name="application.jar", valid=True):
        path = self.directory / "target" / name
        with zipfile.ZipFile(path, "w") as archive:
            if valid:
                archive.writestr("BOOT-INF/classes/static/ui/index.html",
                                 '<script src="/ui/assets/app.js"></script><link rel="stylesheet" href="/ui/assets/app.css">')
                archive.writestr("BOOT-INF/classes/static/ui/assets/app.js", "fixture-js")
                archive.writestr("BOOT-INF/classes/static/ui/assets/app.css", "fixture-css")
        return path

    def run_jar(self):
        return self.run_shell("bash scripts/ci/select-jar.sh target")

    def run_tags(self, publish=False):
        operation = "push" if publish else "check"
        return self.run_shell("python3 scripts/ci/docker-tags.py " + operation)

    def run_shell(self, code):
        return subprocess.run(["bash", "-e", "-o", "pipefail", "-c", code],
                              cwd=self.directory, env=self.environment, capture_output=True, text=True)

    def docker_calls(self):
        return [json.loads(line) for line in self.calls.read_text().splitlines()] if self.calls.exists() else []

    def test_single_complete_jar_passes(self):
        self.jar("application with spaces.jar")
        result = self.run_jar()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "target/application with spaces.jar")

    def test_missing_multiple_or_incomplete_jar_fails(self):
        self.assertNotEqual(self.run_jar().returncode, 0)
        self.jar(valid=False)
        self.assertNotEqual(self.run_jar().returncode, 0)
        self.jar("second.jar")
        self.assertNotEqual(self.run_jar().returncode, 0)

    def test_disjoint_tags_push_in_order(self):
        result = self.run_tags(publish=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.docker_calls(), [["push", tag] for tag in
                         (self.environment["STANDARD_TAGS"] + "\n" + self.environment["ASTRA_TAGS"]).splitlines()])

    def test_overlapping_or_empty_sets_reject_before_push(self):
        self.environment["ASTRA_TAGS"] = self.environment["STANDARD_TAGS"]
        self.assertNotEqual(self.run_tags(publish=True).returncode, 0)
        self.environment["ASTRA_TAGS"] = ""
        self.assertNotEqual(self.run_tags(publish=True).returncode, 0)
        self.assertEqual(self.docker_calls(), [])

    def test_check_does_not_publish(self):
        result = self.run_tags()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.docker_calls(), [])

    def test_invalid_tags_reject_entire_publication_before_push(self):
        original = self.environment.copy()
        for name in ("STANDARD_TAGS", "ASTRA_TAGS"):
            for invalid in ("", "\n", "   ", "example/app:1.2.3\n\nexample/app:latest",
                            "--all-tags", "example/app:bad tag"):
                with self.subTest(name=name, tags=invalid):
                    self.environment = {**original, name: invalid}
                    result = self.run_tags(publish=True)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertEqual(self.docker_calls(), [])

    def test_failed_push_stops_following_pushes(self):
        self.environment["FAIL_TAG"] = "example/app:latest"
        result = self.run_tags(publish=True)
        self.assertEqual(result.returncode, 17, result.stderr)
        self.assertEqual(self.docker_calls(), [["push", "example/app:1.2.3"], ["push", "example/app:latest"]])


if __name__ == "__main__":
    unittest.main()
