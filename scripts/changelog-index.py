#!/usr/bin/env python3
"""Rewrite the release table in `changelog/README.md` from the files beside it.

The index is derived, never hand-maintained: adding `changelog/<version>.md` is
the whole act of publishing a release note. Run by CI after git-cliff writes the
new version's file, and by `just changelog-index` for a local check.
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DIR = ROOT / "changelog"
INDEX = DIR / "README.md"
VERSION = re.compile(r"^(\d{4})\.(\d{2})\.(\d+)$")
HEADING = re.compile(r"^##\s+\S+\s+—\s+(\d{4}-\d{2}-\d{2})\s*$", re.M)
START, END = "<!-- releases:start -->", "<!-- releases:end -->"


def released():
    """Every version file, newest first. Sorted numerically, not lexically."""
    out = []
    for path in DIR.glob("*.md"):
        m = VERSION.match(path.stem)
        if not m:
            continue
        heading = HEADING.search(path.read_text(encoding="utf-8"))
        out.append((tuple(int(g) for g in m.groups()), path.stem, heading.group(1) if heading else ""))
    return [(v, d) for _, v, d in sorted(out, reverse=True)]


def main() -> int:
    rows = released()
    table = ["| Version | Date |", "| --- | --- |"]
    table += [f"| [{v}]({v}.md) | {d} |" for v, d in rows]
    body = "\n".join(table) if rows else "_No releases yet._"

    text = INDEX.read_text(encoding="utf-8")
    if START not in text or END not in text:
        print(f"{INDEX}: missing {START} / {END} markers", file=sys.stderr)
        return 1
    head, rest = text.split(START, 1)
    _, tail = rest.split(END, 1)
    new = f"{head}{START}\n\n{body}\n\n{END}{tail}"

    if new == text:
        return 0
    INDEX.write_text(new, encoding="utf-8")
    print(f"changelog/README.md: {len(rows)} release(s) indexed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
