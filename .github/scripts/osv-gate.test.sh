#!/usr/bin/env bash
# Tests osv-gate.sh against the JSON fixtures. Run: bash .github/scripts/osv-gate.test.sh
set -u
dir=$(cd "$(dirname "$0")" && pwd)
fx=$dir/osv-gate-fixtures
failed=0

expect() { # expected exit code, description, args...
  local want=$1 desc=$2; shift 2
  bash "$dir/osv-gate.sh" "$@" > /dev/null 2>&1
  local got=$?
  if [ "$got" -eq "$want" ]; then echo "ok   $desc"; else echo "FAIL $desc (exit $got, wanted $want)"; failed=1; fi
}

expect 0 "an unfixed finding passes" "$fx/unfixed.json"
expect 1 "a fixable finding fails" "$fx/fixable.json"
expect 0 "no findings pass" "$fx/clean.json"
expect 0 "a fixable finding already on the base passes" "$fx/fixable.json" "$fx/fixable.json"
expect 1 "a fixable finding the PR introduces fails" "$fx/fixable.json" "$fx/clean.json"
expect 0 "an unfixed finding the PR introduces passes" "$fx/unfixed.json" "$fx/clean.json"
expect 1 "a missing results file fails" "$fx/absent.json"
exit "$failed"
