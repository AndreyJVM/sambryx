"""Regression coverage for detecting missing or stale Docker Hub documentation."""

import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("verify_dockerhub", Path(__file__).resolve().parents[1] / "checks/verify-dockerhub.py")
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class DockerHubDocumentationTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.readme = Path(directory.name) / "README.md"
        self.readme.write_text("# Sambryx\n\n## Quick Start\nRun the versioned image.\n", encoding="utf-8")

    def published(self, content):
        return patch.object(verifier.urllib.request, "urlopen", return_value=io.BytesIO(
            json.dumps({"full_description": content}).encode()))

    def test_complete_published_documentation_is_accepted(self):
        with self.published(self.readme.read_text()):
            verifier.verify("example/sambryx", self.readme)

    def test_short_placeholder_is_rejected(self):
        with self.published("Sambryx manages Samba servers."):
            with self.assertRaisesRegex(ValueError, "differs"):
                verifier.verify("example/sambryx", self.readme)

    def test_missing_description_is_rejected(self):
        with self.published(None):
            with self.assertRaisesRegex(ValueError, "differs"):
                verifier.verify("example/sambryx", self.readme)

    def test_empty_or_oversized_utf8_documentation_is_rejected_before_network(self):
        for content in ("", "\u044f" * 12501):
            with self.subTest(bytes=len(content.encode("utf-8"))):
                self.readme.write_text(content, encoding="utf-8")
                with patch.object(verifier.urllib.request, "urlopen") as request:
                    with self.assertRaisesRegex(ValueError, "25000"):
                        verifier.verify("example/sambryx", self.readme)
                    request.assert_not_called()
