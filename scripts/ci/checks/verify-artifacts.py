#!/usr/bin/env python3
"""Verify frontend contents, the packaged JAR, and optional running HTTP endpoints."""

import argparse
import hashlib
from html.parser import HTMLParser
import json
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request
import zipfile


class FrontendReferences(HTMLParser):
    def __init__(self):
        super().__init__()
        self.paths = set()

    def handle_starttag(self, tag, attributes):
        attributes = dict(attributes)
        if tag == "script" and attributes.get("src"):
            self.paths.add(attributes["src"])
        if tag == "link" and attributes.get("rel") == "stylesheet":
            self.paths.add(attributes.get("href", ""))


def frontend_files(jar):
    prefix = "BOOT-INF/classes/static"
    with zipfile.ZipFile(jar) as archive:
        index = archive.read(prefix + "/ui/index.html")
        references = FrontendReferences()
        references.feed(index.decode("utf-8"))
        if not any(path.endswith(".js") for path in references.paths):
            raise ValueError("Packaged UI has no JavaScript bundle")
        if not any(path.endswith(".css") for path in references.paths):
            raise ValueError("Packaged UI has no stylesheet")
        files = {"/ui/": index}
        for reference in references.paths:
            url = urllib.parse.urlsplit(reference)
            if url.scheme or url.netloc or not url.path.startswith("/ui/assets/"):
                raise ValueError("UI bundles must use local /ui/assets/ URLs")
            files[url.path] = archive.read(prefix + urllib.parse.unquote(url.path))
        return files


def verify_http(base_url, files):
    for path, expected in files.items():
        with urllib.request.urlopen(base_url + path, timeout=10) as response:
            if response.read() != expected:
                raise ValueError("Served UI resource differs from JAR: " + path)
    with urllib.request.urlopen(base_url + "/api/auth/environment", timeout=10) as response:
        result = json.load(response)
        if not result.get("success") or result.get("data", {}).get("mode") != "remote":
            raise ValueError("Container must expose the remote execution environment")
    try:
        with urllib.request.urlopen(base_url + "/api/auth/me", timeout=10):
            raise ValueError("Anonymous access to /api/auth/me was allowed")
    except urllib.error.HTTPError as error:
        try:
            if error.code != 401:
                raise
        finally:
            error.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("--url")
    arguments = parser.parse_args()
    files = frontend_files(arguments.jar)
    if arguments.url:
        verify_http(arguments.url.rstrip("/"), files)
    print("Artifact verification passed; JAR SHA-256:", hashlib.sha256(arguments.jar.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
