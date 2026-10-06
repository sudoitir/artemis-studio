#!/usr/bin/env bash
# The OSV gate (ADR-0171). Reads osv-scanner JSON and fails only on a finding that has a fixed
# version; every other finding is a ::warning:: annotation and never fails the run.
#
#   osv-gate.sh NEW.json [OLD.json]
#
# With OLD.json (a pull request) only findings absent from OLD.json count: those the PR introduces.
# A finding is one vulnerability ID of one package version. It is fixable when a `fixed` event
# sits in a range of an `affected` entry for that package, in that package's ecosystem.
set -euo pipefail

new=${1:?usage: osv-gate.sh NEW.json [OLD.json]}
old=${2:-/dev/null}

for f in "$new" "$old"; do
  [ "$f" = /dev/null ] || [ -s "$f" ] || { echo "::error::$f is missing or empty, so there are no scan results to judge"; exit 1; }
done

# One TSV row per finding: name, version, id, fixed versions joined by ", " (empty when unfixed).
# An ecosystem may carry a release suffix ("Debian:12"); only the part before ":" is compared.
rows=$(jq -nr --slurpfile new "$new" --slurpfile old "$old" '
  def eco: split(":")[0];
  def findings:
    [ .results[]?.packages[]? | . as $p
      | $p.vulnerabilities[]? | . as $v
      | { name: $p.package.name, version: $p.package.version, id: $v.id,
          fixed: ([ $v.affected[]?
                    | select(.package.name == $p.package.name
                             and (.package.ecosystem | eco) == ($p.package.ecosystem | eco))
                    | .ranges[]?.events[]? | .fixed? // empty ] | unique | join(", ")) } ]
    | unique_by([.name, .version, .id]);
  ($old | map(findings[] | [.name, .version, .id]) ) as $known
  | ($new[0] | findings)[]
  | select([.name, .version, .id] as $k | $known | index([$k]) | not)
  | [.name, .version, .id, .fixed] | @tsv')

fixable=0
while IFS=$'\t' read -r name version id fixed; do
  [ -n "$id" ] || continue
  if [ -n "$fixed" ]; then
    echo "::error title=Fixable vulnerability::$name $version $id, fixed in $fixed"
    fixable=$((fixable + 1))
  else
    echo "::warning title=Vulnerability without a fix::$name $version $id has no fixed version"
  fi
done <<< "$rows"

if [ "$fixable" -gt 0 ]; then
  echo "$fixable finding(s) have a fixed version: upgrade or override them (ADR-0124)."
  exit 1
fi
echo "No finding with a fixed version."
