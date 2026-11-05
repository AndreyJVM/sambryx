"""Check JAR selection through the real shell entry point."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[3]


class WorkflowScriptsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        (self.directory / "target").mkdir()
        (self.directory / "scripts").symlink_to(ROOT / "scripts", target_is_directory=True)
        self.environment = dict(os.environ)

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
        return self.run_shell("bash scripts/ci/release/select-jar.sh target")

    def run_shell(self, code):
        return subprocess.run(["bash", "-e", "-o", "pipefail", "-c", code],
                              cwd=self.directory, env=self.environment, capture_output=True, text=True)

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



if __name__ == '__main__':
    unittest.main()
