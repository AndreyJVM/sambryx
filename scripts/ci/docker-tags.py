#!/usr/bin/env python3
"""Check or push disjoint STANDARD_TAGS and ASTRA_TAGS from Docker metadata."""

import argparse
import os
import re
import subprocess
import sys


def tag_set(name):
    tags = os.environ.get(name, "").splitlines()
    if not tags or any(not re.fullmatch(
            r"[a-z0-9][a-z0-9./:_-]*:[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}", tag) for tag in tags):
        raise ValueError(name + " must contain nonempty tagged image references, one per line")
    return tags


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("check", "push"))
    arguments = parser.parse_args()
    try:
        standard = tag_set("STANDARD_TAGS")
        astra = tag_set("ASTRA_TAGS")
        if set(standard) & set(astra):
            raise ValueError("Standard and Astra image tags must not overlap")
        if arguments.operation == "push":
            for tag in standard + astra:
                subprocess.run(["docker", "push", tag], check=True)
    except ValueError as error:
        print(str(error), file=sys.stderr)
        return 1
    except subprocess.CalledProcessError as error:
        print("Docker push failed; remaining tags were not published", file=sys.stderr)
        return error.returncode if error.returncode > 0 else 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
