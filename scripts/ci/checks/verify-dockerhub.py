#!/usr/bin/env python3
"""Verify the public Docker Hub README matches the checked-in documentation."""

import argparse
import json
from pathlib import Path
import re
import urllib.request


def verify(repository, readme):
    if not re.fullmatch(r"[a-z0-9_-]+/[a-z0-9_.-]+", repository):
        raise ValueError("Expected a Docker Hub namespace/repository")
    expected = Path(readme).read_text(encoding="utf-8").strip()
    if not expected or len(expected.encode("utf-8")) > 25000:
        raise ValueError("README must contain between 1 and 25000 UTF-8 bytes")
    url = "https://hub.docker.com/v2/repositories/" + repository + "/"
    with urllib.request.urlopen(url, timeout=30) as response:
        actual = json.load(response).get("full_description") or ""
    if actual.strip() != expected:
        raise ValueError("Published Docker Hub README differs from the repository file")
    print("Docker Hub README matches the repository file")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository")
    parser.add_argument("readme", type=Path)
    arguments = parser.parse_args()
    verify(arguments.repository, arguments.readme)
