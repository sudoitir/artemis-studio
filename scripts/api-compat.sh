#!/usr/bin/env bash
#
# Breaking-change gate for the public API (ADR-0148): compares web/openapi.json with the one where the
# branch left BASE, so only the breaks this branch introduces count (main is the last release for API
# purposes: every merge that touches web/ releases). A break passes only when a commit in BASE..HEAD is
# marked as one, the same marker that puts it under "### Breaking" in the release notes (ADR-0051).
#
#   scripts/api-compat.sh [BASE]     BASE defaults to origin/main
#
# Needs history back to the merge base (fetch-depth: 0 in CI) and Docker; oasdiff runs from its image
# pinned by digest.
set -euo pipefail

OASDIFF=tufin/oasdiff:v1.32.1@sha256:3b14fe0112e5d1bf862f91ab234a4bcd161a3f399e98f8b0b665ce70857694ac
BASE=${1:-origin/main}

FORK=$(git merge-base "$BASE" HEAD) || { echo "no merge base with $BASE (fetch its history)" >&2; exit 2; }

dir=$(mktemp -d)
trap 'rm -rf "$dir"' EXIT
chmod 755 "$dir"
git show "$FORK:web/openapi.json" > "$dir/base.json"
cp web/openapi.json "$dir/head.json"
chmod 644 "$dir"/*.json

echo "web/openapi.json against $BASE at $(git rev-parse --short "$FORK")"
status=0
docker run --rm -v "$dir:/spec:ro" "$OASDIFF" breaking --fail-on ERR /spec/base.json /spec/head.json || status=$?
[ "$status" -eq 0 ] && { echo "No breaking change."; exit 0; }
[ "$status" -eq 1 ] || { echo "oasdiff failed (exit $status)" >&2; exit "$status"; }

if git log --format=%s "$BASE..HEAD" | grep -Eq '^[a-z]+(\([^)]*\))?!:' \
  || git log --format=%B "$BASE..HEAD" | grep -Eq '^BREAKING[ -]CHANGE:'; then
  echo "Breaking change, marked by a commit in $BASE..HEAD (\`!:\` or \`BREAKING CHANGE:\`): it will be listed under ### Breaking."
  exit 0
fi
echo "::error::breaking API change with no marking commit. Mark one commit with \`!\` before the colon or a \`BREAKING CHANGE:\` footer, or undo the change." >&2
exit 1
