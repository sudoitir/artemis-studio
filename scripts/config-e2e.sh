#!/usr/bin/env bash
#
# The end-to-end proof for declared broker configuration (ADR-0067), against the
# dev pair `just dev-up` brings up.
#
# It exists because the claims this loop makes cannot be checked by a unit test.
# The apply engine's contract is about a real broker: what `addAddressSettings`
# replaces, what a read-back reports, what a second apply converges to, and what
# happens when the canary refuses. `BrokerConfigApplyServiceTest` mocks the broker
# layer, so it can only prove Studio's half of every one of those sentences.
#
#   ADMIN_PASSWORD=... ./scripts/config-e2e.sh
#
# Everything goes through the product's own surfaces — Studio's REST API and the
# Artemis CLI inside the broker image. The only direct reads are the two
# measurements at the end, which ask the broker what it reports back, because
# that is the question.
#
# Exit status is the number of failed checks, so CI can gate on it. Every check
# prints PASS or FAIL and the run continues: a baseline is worth more complete
# than short.
set -uo pipefail

STUDIO=${STUDIO:-http://localhost:8080}
ADMIN_USER=${ADMIN_USER:-admin}
ADMIN_PASSWORD=${ADMIN_PASSWORD:?set ADMIN_PASSWORD to the password just dev-up printed}
NEW_PASSWORD=${NEW_PASSWORD:-config-e2e-Passw0rd!}
COMPOSE=${COMPOSE:-docker compose -f deploy/compose/compose.dev.yaml}
CLUSTER_NAME=${CLUSTER_NAME:-config-e2e}

JAR=/var/lib/artemis-instance/bin/artemis
COOKIES=$(mktemp)
trap 'rm -f "$COOKIES"' EXIT

FAILURES=0
say() { printf '\n\033[1m→ %s\033[0m\n' "$*"; }
pass() { printf '\033[32m  PASS %s\033[0m\n' "$*"; }
fail() { printf '\033[31m  FAIL %s\033[0m\n' "$*" >&2; FAILURES=$((FAILURES + 1)); }
note() { printf '\033[36m  ·    %s\033[0m\n' "$*"; }
die() { printf '\n\033[31mABORT: %s\033[0m\n' "$*" >&2; exit 99; }

csrf() { awk '$6 == "XSRF-TOKEN" { print $7 }' "$COOKIES" | tail -1; }

# api METHOD PATH [curl args...] → response body on stdout
api() {
  local method=$1 path=$2
  shift 2
  curl -sS -b "$COOKIES" -c "$COOKIES" -X "$method" "$STUDIO/api/v1$path" \
    -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(csrf)" "$@"
}

# status METHOD PATH [curl args...] → HTTP status code on stdout
status() {
  local method=$1 path=$2
  shift 2
  curl -sS -o /dev/null -w '%{http_code}' -b "$COOKIES" -c "$COOKIES" -X "$method" "$STUDIO/api/v1$path" \
    -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(csrf)" "$@"
}

# The import endpoint consumes XML, so it needs its own content type rather than
# api()'s JSON one — sending both is how this script first read every import as a 415.
# xml_import PATH FILE_OR_- → body; xml_import_status PATH FILE_OR_- → HTTP status
xml_import() {
  curl -sS -b "$COOKIES" -c "$COOKIES" -X POST "$STUDIO/api/v1$1" \
    -H 'Content-Type: application/xml' -H "X-XSRF-TOKEN: $(csrf)" --data-binary "@$2"
}
xml_import_status() {
  curl -sS -o /dev/null -w '%{http_code}' -b "$COOKIES" -c "$COOKIES" -X POST "$STUDIO/api/v1$1" \
    -H 'Content-Type: application/xml' -H "X-XSRF-TOKEN: $(csrf)" --data-binary "@$2"
}

# jq-free JSON access: py '<expression over the parsed body in `d`>' <<< "$json"
py() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }

# jolokia SERVICE JSON — ask one broker directly; used only for the out-of-band
# change and the measurements.
jolokia() {
  $COMPOSE exec -T "$1" curl -sS -u artemis:artemis -H 'Content-Type: application/json' \
    -d "$2" http://localhost:8161/console/jolokia/
}

# The broker MBean is named after the broker, not its bind address — the dev pair
# is broker="primary", not the broker="0.0.0.0" the docs' examples use. Ask.
broker_mbean() {
  jolokia "$1" '{"type":"search","mbean":"org.apache.activemq.artemis:broker=*"}' \
    | python3 -c "import json,sys; print(json.load(sys.stdin)['value'][0])"
}

# broker_exec SERVICE OPERATION ARG... — every argument of every operation used here
# is a String, including the settings JSON, so they are passed through as separate
# argv entries and serialised by python. Building this payload in the shell instead
# meant four levels of quoting, and the escaping silently lost the settings body.
broker_exec() {
  local service=$1 operation=$2 mbean
  shift 2
  mbean=$(broker_mbean "$service")
  jolokia "$service" "$(python3 -c "
import json, sys
print(json.dumps({'type': 'exec', 'mbean': sys.argv[1], 'operation': sys.argv[2], 'arguments': sys.argv[3:]}))
" "$mbean" "$operation" "$@")"
}

expect() { # expect LABEL EXPECTED ACTUAL
  if [ "$2" = "$3" ]; then pass "$1"; else fail "$1 — expected '$2', got '$3'"; fi
}

# ── 0. sign in ────────────────────────────────────────────────────────────────

say "waiting for Studio"
for _ in $(seq 1 60); do
  curl -fsS "$STUDIO/actuator/health" >/dev/null 2>&1 && break
  sleep 3
done
curl -fsS "$STUDIO/actuator/health" >/dev/null 2>&1 || die "Studio never became healthy at $STUDIO"

say "signing in"
login() {
  curl -sS -b "$COOKIES" -c "$COOKIES" "$STUDIO/api/v1/auth/me" >/dev/null || true
  api POST /auth/login -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$1\"}" | grep -q '"username"'
}
if login "$ADMIN_PASSWORD"; then
  if curl -sS -b "$COOKIES" -c "$COOKIES" "$STUDIO/api/v1/clusters" | grep -q must-change-password; then
    say "changing the bootstrap password"
    api POST /auth/password \
      -d "{\"currentPassword\":\"$ADMIN_PASSWORD\",\"newPassword\":\"$NEW_PASSWORD\"}" >/dev/null
    ADMIN_PASSWORD=$NEW_PASSWORD
    login "$ADMIN_PASSWORD" || die "login failed after the password change"
  fi
elif login "$NEW_PASSWORD"; then
  ADMIN_PASSWORD=$NEW_PASSWORD
else
  die "login failed — is ADMIN_PASSWORD the one just dev-up printed?"
fi
pass "signed in as $ADMIN_USER"

# ── 1. first launch → register ────────────────────────────────────────────────

say "1. registering the dev pair"
existing=$(api GET /clusters | py "next((c['id'] for c in d if c['name']=='$CLUSTER_NAME'), '')")
[ -n "$existing" ] && api DELETE "/clusters/$existing" >/dev/null

check=$(api POST '/clusters?dryRun=true' -d "{
  \"seedUrls\": [\"http://artemis-primary:8161/console/jolokia\", \"http://artemis-backup:8161/console/jolokia\"],
  \"name\": \"$CLUSTER_NAME\",
  \"credentials\": {\"username\": \"artemis\", \"password\": \"artemis\"},
  \"coreCredentials\": {\"username\": \"artemis\", \"password\": \"artemis\"}
}")
note "connection check: $(py "json.dumps(d)[:200]" <<<"$check" 2>/dev/null || echo unparsed)"
# The check is where a first-launch operator meets their capability gaps.
if py "'recommend' in json.dumps(d).lower()" <<<"$check" | grep -q True; then
  pass "P-9 the connection check offers recommended configuration"
else
  fail "P-9 the connection check reports gaps but offers nothing to apply — copy-only"
fi

CLUSTER=$(api POST /clusters -d "{
  \"seedUrls\": [\"http://artemis-primary:8161/console/jolokia\", \"http://artemis-backup:8161/console/jolokia\"],
  \"name\": \"$CLUSTER_NAME\",
  \"description\": \"configuration end-to-end\",
  \"credentials\": {\"username\": \"artemis\", \"password\": \"artemis\"},
  \"coreCredentials\": {\"username\": \"artemis\", \"password\": \"artemis\"}
}" | py "d['id']")
[ -n "$CLUSTER" ] || die "cluster registration returned no id"
pass "registered cluster $CLUSTER"

say "waiting for topology to report a live node"
for _ in $(seq 1 30); do
  LIVE=$(api GET "/clusters/$CLUSTER/topology" \
    | py "sum(1 for n in d['nodes'] for e in n['endpoints'] if e.get('active'))" 2>/dev/null || echo 0)
  [ "${LIVE:-0}" -ge 1 ] && break
  sleep 2
done
[ "${LIVE:-0}" -ge 1 ] || die "no live node after 60s"
pass "$LIVE live node(s)"

# ── 2. adopt → declare ────────────────────────────────────────────────────────

say "2. adopting the running configuration"
adoption=$(api POST "/clusters/$CLUSTER/config/adopt")
ADOPT_SETTINGS=$(py "len(d['document']['addressSettings'])" <<<"$adoption")
note "adoption proposes $ADOPT_SETTINGS address-setting match(es), \
$(py "len(d['document']['securitySettings'])" <<<"$adoption") security-setting(s), \
$(py "len(d['document']['diverts'])" <<<"$adoption") divert(s)"
note "disagreements between nodes: $(py "len(d.get('disagreements', []))" <<<"$adoption")"

# DEFECT PROBE P-1: does the adoption preview disclose the drift it closes?
if py "'closes' in json.dumps(d).lower() or 'findings' in json.dumps(d).lower()" <<<"$adoption" | grep -q True; then
  pass "P-1 adoption preview mentions the findings it closes"
else
  fail "P-1 adoption preview does not disclose the drift findings it will erase"
fi

say "saving the adopted document as revision 1"
doc=$(py "json.dumps(d['document'])" <<<"$adoption")
saved=$(api PUT "/clusters/$CLUSTER/config" -d "{\"document\": $doc, \"expectedRevision\": 0, \"note\": \"adopted by config-e2e\"}")
REVISION=$(py "d['revision']" <<<"$saved" 2>/dev/null || echo "")
[ -n "$REVISION" ] || die "save returned no revision: $saved"
pass "revision $REVISION saved"

# DEFECT PROBE P-1b: is the adoption recorded as ADOPT, or laundered as EDIT?
src=$(api GET "/clusters/$CLUSTER/config/revisions" | py "d[0]['source']")
expect "P-1b revision records its source as ADOPT" "ADOPT" "$src"

# ── 3. dry run → apply → verify ───────────────────────────────────────────────

say "3. dry run of the adopted declaration (expect: nothing to do)"
plan=$(api POST "/clusters/$CLUSTER/config/apply?dryRun=true" -d '{}')
STEPS=$(py "d['plan']['stepCount']" <<<"$plan")
PLANHASH=$(py "d['plan']['planHash']" <<<"$plan")
expect "an adopted declaration plans zero steps" "0" "$STEPS"

say "declaring something the broker does not have"
# A queue-level setting on a match of our own: additive, reversible, and not a
# policy that could lose a message.
doc2=$(python3 -c "
import json, sys
d = json.load(sys.stdin)
doc = d['document']
doc['addressSettings'].append({'match': 'CONFIG.E2E.#', 'values': {'maxDeliveryAttempts': 7}})
print(json.dumps(doc))" <<<"$adoption")
saved=$(api PUT "/clusters/$CLUSTER/config" -d "{\"document\": $doc2, \"expectedRevision\": $REVISION, \"note\": \"config-e2e probe setting\"}")
PREVIOUS_REVISION=$REVISION
REVISION=$(py "d['revision']" <<<"$saved" 2>/dev/null || echo "")
[ -n "$REVISION" ] || die "save of the probe setting returned no revision: $saved"
pass "revision $REVISION declares CONFIG.E2E.#"

plan=$(api POST "/clusters/$CLUSTER/config/apply?dryRun=true" -d '{}')
STEPS=$(py "d['plan']['stepCount']" <<<"$plan")
PLANHASH=$(py "d['plan']['planHash']" <<<"$plan")
CANARY=$(py "d['plan']['canaryNodeId']" <<<"$plan")
HAZARDS=$(py "json.dumps(d['plan'].get('highHazardIds', []))" <<<"$plan")
note "plan: $STEPS step(s), canary $CANARY, high hazards $HAZARDS"
[ "$STEPS" -ge 1 ] && pass "the new setting produced a step" || fail "no step planned for a setting the broker lacks"

say "stale planHash is refused"
code=$(status POST "/clusters/$CLUSTER/config/apply?dryRun=false" \
  -d "{\"expectedPlanHash\": \"deadbeef\", \"acknowledgedHazards\": $HAZARDS}")
expect "stale planHash → 409" "409" "$code"

say "stale revision is refused"
code=$(status POST "/clusters/$CLUSTER/config/apply?dryRun=false" \
  -d "{\"revision\": $PREVIOUS_REVISION, \"expectedPlanHash\": \"$PLANHASH\", \"acknowledgedHazards\": $HAZARDS}")
expect "stale revision → 409" "409" "$code"

say "applying for real"
outcome=$(api POST "/clusters/$CLUSTER/config/apply?dryRun=false" \
  -d "{\"expectedPlanHash\": \"$PLANHASH\", \"acknowledgedHazards\": $HAZARDS}")
OUTCOME=$(py "d['outcome']" <<<"$outcome")
APPLY_ID=$(py "d['applyId']" <<<"$outcome" 2>/dev/null || echo "")
note "outcome: $OUTCOME — $(py "d['summary']" <<<"$outcome")"
expect "apply reports APPLIED" "APPLIED" "$OUTCOME"

say "every step was verified by read-back"
unverified=$(py "sum(1 for n in d['nodes'] for s in n['steps'] if s['status']=='APPLIED' and s['verified']!='VERIFIED')" <<<"$outcome")
expect "no applied step left unverified" "0" "$unverified"

say "re-running converges (every step ALREADY, nothing written)"
plan=$(api POST "/clusters/$CLUSTER/config/apply?dryRun=true" -d '{}')
expect "second dry run plans zero steps" "0" "$(py "d['plan']['stepCount']" <<<"$plan")"

# ── 4. drift ──────────────────────────────────────────────────────────────────

say "4. drift evaluation after a verified apply"
report=$(api POST "/clusters/$CLUSTER/config/drift/evaluate")
drifted=$(py "sum(1 for n in d['nodes'] if n['state']=='DRIFTED')" <<<"$report")
expect "no node drifted right after a verified apply" "0" "$drifted"

# DEFECT PROBE P-1/D-a: does IN_SYNC say why?
if py "all(n.get('basis') for n in d['nodes'] if n['state']=='IN_SYNC')" <<<"$report" | grep -q True; then
  pass "D-a every IN_SYNC node states its basis"
else
  fail "D-a a node is IN_SYNC with no basis recorded (verified apply / adoption / observed match)"
fi

say "changing the broker underneath the declaration"
# Take the setting away on the primary only; the declaration still wants it.
removal=$(broker_exec artemis-primary 'removeAddressSettings(java.lang.String)' 'CONFIG.E2E.#')
py "d['status']" <<<"$removal" | grep -q 200 || fail "the out-of-band removal itself failed: $(head -c 200 <<<"$removal")"
report=$(api POST "/clusters/$CLUSTER/config/drift/evaluate")
drifted=$(py "sum(1 for n in d['nodes'] if n['state']=='DRIFTED')" <<<"$report")
[ "$drifted" -ge 1 ] && pass "drift is seen after an out-of-band change" || fail "an out-of-band removal produced no drift"

say "adopting while drift is open"
adoption=$(api POST "/clusters/$CLUSTER/config/adopt")
doc3=$(py "json.dumps(d['document'])" <<<"$adoption")
api PUT "/clusters/$CLUSTER/config" \
  -d "{\"document\": $doc3, \"expectedRevision\": $REVISION, \"note\": \"adopt over open drift\"}" >/dev/null
report=$(api POST "/clusters/$CLUSTER/config/drift/evaluate")
after=$(py "sum(1 for n in d['nodes'] if n['state']=='DRIFTED')" <<<"$report")
note "drifted nodes after adoption: $after (was $drifted) — the broker was NOT written"
if [ "$after" -lt "$drifted" ]; then
  fail "P-1 adoption erased $((drifted - after)) drift finding(s) with no broker write and no disclosure"
else
  pass "P-1 adoption did not silently erase drift"
fi
REVISION=$(api GET "/clusters/$CLUSTER/config" | py "d['revision']")

# ── 5. concurrency, caps, modes ───────────────────────────────────────────────

say "5. two applies at once"
# The race only exists while an apply is in flight, and an apply with nothing to do
# returns immediately — so give it real work first. Several matches, so the run is
# long enough for the second call to arrive inside it.
doc4=$(python3 -c "
import json, sys
doc = json.load(sys.stdin)['document']
for i in range(12):
    doc['addressSettings'].append({'match': f'CONFIG.E2E.RACE{i}.#', 'values': {'maxDeliveryAttempts': 5}})
print(json.dumps(doc))" <<<"$(api GET "/clusters/$CLUSTER/config")")
saved=$(api PUT "/clusters/$CLUSTER/config" -d "{\"document\": $doc4, \"expectedRevision\": $REVISION, \"note\": \"config-e2e race probe\"}")
REVISION=$(py "d['revision']" <<<"$saved" 2>/dev/null || echo "$REVISION")

plan=$(api POST "/clusters/$CLUSTER/config/apply?dryRun=true" -d '{}')
note "race plan: $(py "d['plan']['stepCount']" <<<"$plan") step(s)"
PLANHASH=$(py "d['plan']['planHash']" <<<"$plan")
HAZARDS=$(py "json.dumps(d['plan'].get('highHazardIds', []))" <<<"$plan")
status POST "/clusters/$CLUSTER/config/apply?dryRun=false" \
  -d "{\"expectedPlanHash\": \"$PLANHASH\", \"acknowledgedHazards\": $HAZARDS}" >/tmp/config-e2e-a.code &
first=$!
second=$(status POST "/clusters/$CLUSTER/config/apply?dryRun=false" \
  -d "{\"expectedPlanHash\": \"$PLANHASH\", \"acknowledgedHazards\": $HAZARDS}")
wait $first
firstcode=$(cat /tmp/config-e2e-a.code)
note "concurrent applies returned $firstcode and $second"
if [ "$firstcode" = "409" ] || [ "$second" = "409" ]; then
  pass "one of two concurrent applies was refused with 409"
elif [ "$firstcode" = "200" ] && [ "$second" = "200" ]; then
  # Both succeeding proves nothing on its own: the first may simply have finished
  # before the second arrived. Only a plan that still had steps makes it a finding.
  note "both returned 200 — inconclusive unless the run was still in flight; see the step count above"
  fail "two concurrent applies both proceeded ($firstcode, $second)"
else
  fail "unexpected pair of apply results ($firstcode, $second)"
fi

say "config-managed mode refuses an apply server-side"
api PATCH "/clusters/$CLUSTER/config/mode" \
  -d '{"applyMode": "CONFIG_MANAGED", "reportUndeclared": false, "undeclaredExclusions": []}' >/dev/null
plan=$(api POST "/clusters/$CLUSTER/config/apply?dryRun=true" -d '{}')
PLANHASH=$(py "d['plan']['planHash']" <<<"$plan")
code=$(status POST "/clusters/$CLUSTER/config/apply?dryRun=false" \
  -d "{\"expectedPlanHash\": \"$PLANHASH\", \"acknowledgedHazards\": []}")
if [ "$code" = "409" ] || [ "$code" = "422" ]; then
  pass "P-5 a config-managed cluster refuses apply over HTTP ($code)"
else
  fail "P-5 a config-managed cluster applied over HTTP anyway ($code) — only the UI gates it"
fi
api PATCH "/clusters/$CLUSTER/config/mode" \
  -d '{"applyMode": "STUDIO_MANAGED", "reportUndeclared": false, "undeclaredExclusions": []}' >/dev/null

# ── 6. import hardening ───────────────────────────────────────────────────────

say "6. import refuses what it cannot honour"
unknown=$(mktemp)
printf '%s' '<core><address-settings><address-setting match="#"><max-size-byte>10</max-size-byte></address-setting></address-settings></core>' >"$unknown"
code=$(xml_import_status "/clusters/$CLUSTER/config/import-xml" "$unknown")
body=$(xml_import "/clusters/$CLUSTER/config/import-xml" "$unknown")
note "unknown-key import returned $code"
if py "any('max-size-byte' in json.dumps(v) for v in d.get('violations', []))" <<<"$body" | grep -q True; then
  pass "P-8 an unknown address-setting key is a violation"
else
  fail "P-8 an unknown key ('max-size-byte') is reported as merely unsupported, not refused (ADR-0067 D10)"
  note "     import said: $(py "json.dumps(d.get('unsupported', d))[:200]" <<<"$body" 2>/dev/null || echo "$body" | head -c 200)"
fi
rm -f "$unknown"

say "import refuses an XXE payload"
xxefile=$(mktemp)
printf '%s' '<?xml version="1.0"?><!DOCTYPE core [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><core><address-settings><address-setting match="&xxe;"/></address-settings></core>' >"$xxefile"
body=$(xml_import "/clusters/$CLUSTER/config/import-xml" "$xxefile")
if grep -q "root:" <<<"$body"; then
  fail "SECURITY external entity was resolved by the import parser"
else
  pass "external entities are not resolved"
fi
rm -f "$xxefile"

say "import refuses an oversized payload"
bigfile=$(mktemp)
python3 -c "
import sys
sys.stdout.write('<core><address-settings>')
sys.stdout.write('<address-setting match=\"A.#\"><max-delivery-attempts>1</max-delivery-attempts></address-setting>' * 40000)
sys.stdout.write('</address-settings></core>')" >"$bigfile"
code=$(xml_import_status "/clusters/$CLUSTER/config/import-xml" "$bigfile")
if [ "$code" = "413" ] || [ "$code" = "422" ]; then
  pass "P-7 an oversized import is refused ($code)"
else
  fail "P-7 a $(wc -c <"$bigfile")-byte import was accepted with $code — no size cap"
fi
rm -f "$bigfile"

say "export → import round-trips"
roundtrip=$(mktemp)
curl -sS -b "$COOKIES" -c "$COOKIES" "$STUDIO/api/v1/clusters/$CLUSTER/config/export-xml" >"$roundtrip"
body=$(xml_import "/clusters/$CLUSTER/config/import-xml" "$roundtrip")
# An error body carries neither key, so assert the parse actually happened.
if ! py "'sections' in d or 'document' in d or 'unsupported' in d" <<<"$body" | grep -q True; then
  fail "round-trip import did not return a parse result: $(head -c 200 <<<"$body")"
fi
unsupported=$(py "len(d.get('unsupported', []))" <<<"$body" 2>/dev/null || echo '?')
violations=$(py "len(d.get('violations', []))" <<<"$body" 2>/dev/null || echo '?')
expect "Studio's own export imports without violations" "0" "$violations"
rm -f "$roundtrip"
note "round-trip reported $unsupported unsupported element(s)"

# ── 7. the two measurements only a live broker settles ────────────────────────

say "7. what the broker reports back (feeds notes §15/§16)"
# M8 must SET the key before concluding anything: §15 M1 established that a key set
# nowhere is simply absent, so reading '#' and finding no threshold proves only that
# the dev broker.xml does not set one.
broker_exec artemis-primary 'addAddressSettings(java.lang.String,java.lang.String)' \
  'probe.slow.#' \
  '{"slowConsumerThreshold":1,"slowConsumerThresholdMeasurementUnit":"MESSAGES_PER_SECOND","slowConsumerPolicy":"NOTIFY","slowConsumerCheckPeriod":5}' >/dev/null
probe=$(broker_exec artemis-primary 'getAddressSettingsAsJSON(java.lang.String)' 'probe.slow.#')
# The operation returns the entry as a JSON *string*, so its quotes arrive escaped:
# the body reads \"slowConsumerThreshold\":1, and a pattern with a bare quote misses.
if grep -q 'slowConsumerThreshold' <<<"$probe"; then
  pass "M8 slowConsumerThreshold IS echoed once set — the key is verifiable, and a plan may declare it"
  note "     CapabilityProbe's reason text still says the opposite (defect P-10)"
else
  note "M8 slowConsumerThreshold is not echoed even when set — UNVERIFIABLE;"
  note "   a plan that sets it must say so rather than report MISMATCH forever"
fi
broker_exec artemis-primary 'removeAddressSettings(java.lang.String)' 'probe.slow.#' >/dev/null

settings=$(broker_exec artemis-primary 'getAddressSettingsAsJSON(java.lang.String)' '#')
note "keys reported: $(python3 -c "
import json, sys
v = json.load(sys.stdin)['value']
print(len(json.loads(v) if isinstance(v, str) else v))" <<<"$settings" 2>/dev/null || echo '?')"

broker_exec artemis-primary \
  'addSecuritySettings(java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String)' \
  CONFIG.E2E.ROLES amq amq amq amq amq amq amq amq amq amq amq >/dev/null
roles=$(broker_exec artemis-primary 'getRolesAsJSON(java.lang.String)' 'CONFIG.E2E.ROLES')
# §15 M7: the keys come back, but as false — presence proves nothing, the value does.
viewvalue=$(python3 -c "
import json, sys
v = json.load(sys.stdin)['value']
roles = json.loads(v) if isinstance(v, str) else v
print(next((r.get('view') for r in roles if r.get('name') == 'amq'), 'absent'))" <<<"$roles" 2>/dev/null || echo unparsed)
if [ "$viewvalue" = "True" ]; then
  note "M9 view/edit role types ARE reported back — verifiable"
else
  note "M9 view reads back as '$viewvalue' after being granted — unverifiable, as notes §15 M7 recorded"
fi

# ── done ──────────────────────────────────────────────────────────────────────

say "cleaning up"
broker_exec artemis-primary 'removeSecuritySettings(java.lang.String)' 'CONFIG.E2E.ROLES' >/dev/null 2>&1
rm -f /tmp/config-e2e-a.code

printf '\n'
if [ "$FAILURES" -eq 0 ]; then
  printf '\033[32m%s\033[0m\n' "all checks passed"
else
  printf '\033[31m%s\033[0m\n' "$FAILURES check(s) failed — each is a numbered defect for the phase list"
fi
exit "$FAILURES"
