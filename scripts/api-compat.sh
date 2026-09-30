#!/usr/bin/env bash
#
# Breaking-change gate for the public API (ADR-0147): compares web/openapi.json with the one at the
# latest CalVer tag. A break passes only when a commit in BASE..HEAD is marked as one, the same
# marker that puts it under "### Breaking" in the release notes (ADR-0051).
#
#   scripts/api-compat.sh [BASE]     BASE defaults to origin/main
#
# Needs the tags (fetch-depth: 0 in CI) and Docker; oasdiff runs from its image pinned by digest.
set -euo pipefail

OASDIFF=tufin/oasdiff:v1.32.1@sha256:3b14fe0112e5d1bf862f91ab234a4bcd161a3f399e98f8b0b665ce70857694ac
BASE=${1:-origin/main}

TAG=$(git tag -l | grep -E '^[0-9]{4}\.[0-9]{2}\.[0-9]+$' | sort -V | tail -1)
[ -n "$TAG" ] || { echo "no CalVer tag to compare against (fetch the tags)" >&2; exit 2; }

dir=$(mktemp -d)
trap 'rm -rf "$dir"' EXIT
chmod 755 "$dir"
git show "$TAG:web/openapi.json" > "$dir/base.json"
cp web/openapi.json "$dir/head.json"
chmod 644 "$dir"/*.json

echo "web/openapi.json against $TAG"
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
