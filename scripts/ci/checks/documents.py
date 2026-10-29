"""Check local documentation references and passive SVGs without network requests."""
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import unquote, urlsplit
import xml.etree.ElementTree as ET


def validate_svg(path):
    try:
        text = path.read_text(encoding="utf-8")
        if text.startswith("\ufeff") or re.search(r"<!DOCTYPE|<!ENTITY", text, re.I):
            raise ValueError("BOM or XML entities are prohibited")
        root = ET.fromstring(text)
        if root.tag.split("}")[-1] != "svg":
            raise ValueError("Expected SVG root")
        for element in root.iter():
            if element.tag.split("}")[-1] in {"script", "foreignObject", "style", "animate", "animateMotion", "animateTransform", "set"}:
                raise ValueError("Active SVG content is prohibited")
            for key, value in element.attrib.items():
                key = key.split("}")[-1].lower()
                if key.startswith("on") or (key in {"href", "src"} and not value.startswith("#")):
                    raise ValueError("Active or external SVG reference is prohibited")
                if re.search(r"url\(\s*['\"]?(?!#)", value, re.I):
                    raise ValueError("External SVG resource is prohibited")
        return []
    except (OSError, ValueError, ET.ParseError) as error:
        return [f"{path}: {error}"]


def anchors(text):
    found = set(re.findall(r'\b(?:id|name)=[\"\']([^\"\']+)', text))
    found.update(re.findall(r"\{#([^} ]+)\}", text))
    counts = {}
    for heading in re.findall(r"^#{1,6}\s+(.+?)\s*#*\s*$", text, re.M):
        heading = re.sub(r"<[^>]*>", "", heading).lower()
        slug = re.sub(r"[^\w\- ]", "", heading).replace(" ", "-")
        count = counts.get(slug, 0)
        counts[slug] = count + 1
        found.add(slug + (f"-{count}" if count else ""))
    return found


def validate(root, files=None):
    root = root.resolve()
    files = list(files) if files is not None else list(root.rglob("*.md"))
    errors = []
    for path in files:
        path = root / path
        if path.suffix.lower() != ".md":
            continue
        try:
            text = path.read_text(encoding="utf-8")
            if text.startswith("\ufeff"):
                raise ValueError("UTF-8 BOM is prohibited")
            # Exclude fenced examples and inline code before inspecting references.
            text = re.sub(r"```.*?```|~~~.*?~~~", "", text, flags=re.S)
            text = re.sub(r"`[^`\n]+`", "", text)
            links = re.findall(r'\b(?:href|src)=[\"\']([^\"\']+)', text)
            links += re.findall(r'!?\[[^\]]*\]\(<?([^\s)>]+)>?(?:\s+[\"\'][^)]*)?\)', text)
            links += re.findall(r'^\s*\[[^\]]+\]:\s*<?([^\s>]+)', text, flags=re.M)
            for link in links:
                url = urlsplit(link)
                if url.scheme or url.netloc:
                    continue
                target = (path.parent / unquote(url.path)).resolve() if url.path else path
                if not target.is_relative_to(root) or not target.exists():
                    errors.append(f"{path.relative_to(root)}: missing local target {link}")
                elif url.fragment and target.suffix == ".md":
                    if unquote(url.fragment) not in anchors(target.read_text(encoding="utf-8")):
                        errors.append(f"{path.relative_to(root)}: missing anchor {link}")
        except (OSError, ValueError) as error:
            errors.append(f"{path}: {error}")
    return errors


def main():
    root = Path.cwd()
    files = [Path(path) for path in subprocess.check_output(["git", "ls-files", "-z"]).decode("utf-8").split("\0") if path]
    if sys.argv[1:] == ["assets"]:
        errors = [error for path in files if path.suffix == ".svg" for error in validate_svg(root / path)]
    else:
        errors = validate(root, files)
    for error in errors:
        print(error, file=sys.stderr)
    return bool(errors)


if __name__ == "__main__":
    sys.exit(main())
