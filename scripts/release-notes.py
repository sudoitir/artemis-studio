#!/usr/bin/env python3
"""Finish a release's changelog file and produce the GitHub release body.

Runs after git-cliff has written `changelog/<version>.md` from the commits in
this release. It splices in `changelog/unreleased.md` when that optional file
exists (hand-written notes no single commit could carry), and writes
`release-notes.md` — the same content without its version heading,
which is what the GitHub release shows.

Usage: scripts/release-notes.py <version>
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DIR = ROOT / "changelog"
COMMENT = re.compile(r"\A\s*<!--.*?-->\s*", re.S)


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    version = argv[1]
    target = DIR / f"{version}.md"
    if not target.exists():
        print(f"{target}: not generated", file=sys.stderr)
        return 1

    # git-cliff emits an empty `header`, so the file can open on a blank line.
    text = target.read_text(encoding="utf-8").lstrip("\n")
    heading, _, body = text.partition("\n")
    if not heading.startswith("## "):
        print(f"{target}: expected a version heading, got {heading!r}", file=sys.stderr)
        return 1

    pending = DIR / "unreleased.md"
    if pending.exists():
        # The file leads with an HTML comment explaining itself to whoever opens
        # it; that is guidance for authors, not a release note.
        notes = COMMENT.sub("", pending.read_text(encoding="utf-8")).strip()
        if notes:
            body = f"\n{notes}\n{body}"
        pending.unlink()

    body = body.strip("\n")
    target.write_text(f"{heading}\n\n{body}\n", encoding="utf-8")
    (ROOT / "release-notes.md").write_text(f"{body}\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
