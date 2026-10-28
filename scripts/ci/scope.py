"""Select checks from complete Git ranges and aggregate job results fail closed."""
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys

FULL = {"documents", "mkdocs", "assets", "frontend", "backend", "package", "browser", "integration"}
JOBS = {"documents": "documents", "assets": "assets", "frontend": "frontend",
        "backend": "backend", "build-and-test": "package", "browser-smoke": "browser",
        "production-compose-smoke": "integration"}
BRANDING = {f"frontend/public/sambryx/{name}.svg" for name in
            ("logo-full-dark", "logo-full-light", "logo-mark", "favicon")}


def select(paths):
    selected = set()
    for path in paths:
        if path in BRANDING:
            selected.update({"documents", "assets", "frontend", "package", "browser"})
        elif path == "README.md" or (path.endswith(".md") and path.startswith(("docs/", "docker/"))):
            selected.add("documents")
            if path.startswith("docs/"):
                selected.add("mkdocs")
        elif path.startswith(("src/main/java/", "src/test/java/")):
            selected.update({"backend", "package"})
        elif path.startswith(("frontend/src/", "frontend/tests/", "frontend/e2e/")) and PurePosixPath(path).suffix in {".ts", ".tsx", ".js", ".mjs", ".css"}:
            selected.update({"frontend", "package", "browser"})
        else:
            return set(FULL)
    return selected or set(FULL)


def parse_diff(data):
    if not data:
        return []
    if not data.endswith(b"\0"):
        raise ValueError("Truncated Git diff")
    tokens = data[:-1].decode("utf-8", errors="strict").split("\0")
    paths = []
    while tokens:
        status = tokens.pop(0)
        if not re.fullmatch(r"[AMD]|[RC][0-9]{1,3}", status, flags=re.ASCII):
            raise ValueError("Unsupported Git diff status")
        count = 2 if status[0] in "RC" else 1
        if len(tokens) < count:
            raise ValueError("Missing Git diff path")
        for _ in range(count):
            path = tokens.pop(0)
            if not path or path.startswith("/") or ".." in PurePosixPath(path).parts:
                raise ValueError("Invalid Git diff path")
            paths.append(path)
    return paths


def git(*args):
    return subprocess.run(["git", *args], check=True, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE).stdout


def revision(value):
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{40}", value) or value == "0" * 40:
        raise ValueError("Unavailable revision")
    git("cat-file", "-e", value + "^{commit}")
    return value


def changes(event, name, ref):
    if name == "pull_request":
        request = event["pull_request"]
        base, head = revision(request["base"]["sha"]), revision(request["head"]["sha"])
        # A shallow history could yield an incorrect merge base or incomplete diff.
        if git("rev-parse", "--is-shallow-repository").strip() != b"false":
            raise ValueError("Shallow repository")
        bases = git("merge-base", "--all", base, head).decode("ascii").splitlines()
        if len(bases) != 1:
            raise ValueError("Ambiguous merge base")
        base = bases[0]
    elif name == "push" and ref.startswith("refs/heads/"):
        base, head = revision(event["before"]), revision(event["after"])
        if git("rev-parse", "--is-shallow-repository").strip() != b"false":
            raise ValueError("Shallow repository")
    else:
        raise ValueError("Full verification event")
    return parse_diff(git("diff", "--name-status", "-z", "--find-renames", base, head, "--"))


def plan(selected):
    return {job: area in selected for job, area in JOBS.items()}


def gate(expected, needs):
    if not any(expected.values()) or set(expected) != set(JOBS) or any(type(value) is not bool for value in expected.values()):
        return False
    if set(needs) != {*JOBS, "classify"} or needs["classify"].get("result") != "success":
        return False
    return all(needs[job].get("result") == ("success" if required else "skipped")
               for job, required in expected.items())


def main():
    if sys.argv[1:] == ["classify"]:
        try:
            event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"))
            paths = changes(event, os.environ["GITHUB_EVENT_NAME"], os.environ["GITHUB_REF"])
            selected = select(paths)
            reason = "complete Git diff" if paths else "empty diff; full verification"
        except (ValueError, KeyError, TypeError, OSError, subprocess.CalledProcessError) as error:
            selected = set(FULL)
            reason = "full verification: " + type(error).__name__
        print(reason)
        outputs = {**{area: area in selected for area in FULL}, "plan": plan(selected)}
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            for key, value in sorted(outputs.items()):
                output.write(f"{key}={json.dumps(value, separators=(',', ':'))}\n")
    elif sys.argv[1:] == ["gate"]:
        try:
            expected = json.loads(os.environ["EXPECTED_PLAN"])
            needs = json.loads(os.environ["JOB_RESULTS"])
            passed = gate(expected, needs)
        except (ValueError, KeyError, TypeError, AttributeError):
            passed = False
        print("Quality Gate passed" if passed else "Quality Gate failed: invalid classification or job results")
        return 0 if passed else 1
    else:
        raise ValueError("Expected classify or gate")
    return 0


if __name__ == "__main__":
    sys.exit(main())
