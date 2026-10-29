"""Validate local documentation references and safe static SVG assets."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("documents", Path(__file__).resolve().parents[1] / "checks/documents.py")
documents = importlib.util.module_from_spec(spec)
spec.loader.exec_module(documents)


class DocumentsTest(unittest.TestCase):
    def test_local_links_images_anchors_and_utf8(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "README.md").write_text('[Guide](guide.md#installation) <img src="logo.svg">')
            (root / "guide.md").write_text('# Installation\n')
            (root / "logo.svg").write_text('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"/>')
            self.assertEqual(documents.validate(root), [])
            (root / "guide.md").write_text('# Changed\n')
            self.assertTrue(documents.validate(root))
            (root / "README.md").write_bytes(b'\xff')
            self.assertTrue(documents.validate(root))

    def test_deleted_targets_and_unsafe_svg_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "README.md").write_text('[Missing](missing.md)')
            self.assertTrue(documents.validate(root))
            for content in ('<svg><script/></svg>', '<svg><style>@import url(https://example.invalid/x);</style></svg>',
                            '<svg><animate attributeName="href" to="https://example.invalid"/></svg>', '<svg><image href="https://example.invalid/a"/></svg>',
                            '<!DOCTYPE svg [<!ENTITY x SYSTEM "file:///etc/passwd">]><svg/>', '<broken'):
                (root / "README.md").write_text('')
                asset = root / "logo.svg"
                asset.write_text(content)
                with self.subTest(content=content):
                    self.assertTrue(documents.validate_svg(asset))

    def test_mkdocs_explicit_anchor_is_respected(self):
        self.assertIn("custom-id", documents.anchors("## Heading {#custom-id}\n"))
