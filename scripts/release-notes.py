#!/usr/bin/env python3
"""Produce the GitHub release body from git-cliff's notes for this release.

git-cliff renders the commits since the previous tag; this drops its version
heading and splices in `changelog/unreleased.md` when that optional file changed
since the previous release (hand-written notes no single commit could carry).
The release commits nothing to the repository (ADR-0129), so the file is never
deleted: a note that was not edited since the last release is not repeated.

Usage: scripts/release-notes.py <cliff-output> <release-notes-output>
"""

import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
PENDING = ROOT / "changelog" / "unreleased.md"
CALVER = re.compile(r"^\d{4}\.\d{2}\.\d+$")
COMMENT = re.compile(r"\A\s*<!--.*?-->\s*", re.S)


def git(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True)


def previous_release() -> str | None:
    tags = [t for t in git("tag", "-l").stdout.split() if CALVER.match(t)]
    return max(tags, key=lambda t: tuple(int(p) for p in t.split(".")), default=None)


def pending_note() -> str:
    """The unreleased note, when it exists and changed since the previous release."""
    if not PENDING.exists():
        return ""
    last = previous_release()
    rel = PENDING.relative_to(ROOT).as_posix()
    if last and git("diff", "--quiet", last, "HEAD", "--", rel).returncode == 0:
        return ""
    # The file leads with an HTML comment explaining itself to whoever opens it;
    # that is guidance for authors, not a release note.
    return COMMENT.sub("", PENDING.read_text(encoding="utf-8")).strip()


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    source, target = pathlib.Path(argv[1]), pathlib.Path(argv[2])
    # git-cliff emits an empty `header`, so the output can open on a blank line.
    heading, _, body = source.read_text(encoding="utf-8").lstrip("\n").partition("\n")
    if not heading.startswith("## "):
        print(f"{source}: expected a version heading, got {heading!r}", file=sys.stderr)
        return 1
    note = pending_note()
    if note:
        body = f"\n{note}\n{body}"
    target.write_text(f"{body.strip(chr(10))}\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
