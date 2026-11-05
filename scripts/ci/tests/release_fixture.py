"""Load the release CLI for local tests without executing publication."""

import importlib.util
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('release', Path(__file__).resolve().parents[1] / 'release/release.py')
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)
SHA = 'a' * 40
DIGEST = 'sha256:' + 'b' * 64
