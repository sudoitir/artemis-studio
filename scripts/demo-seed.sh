#!/usr/bin/env bash
#
# Fill the demo stack with traffic that looks like a working estate.
#
# Everything here goes through the product's own surfaces: the cluster is
# registered over Studio's REST API, and the messages are produced and consumed
# by the Artemis CLI that already ships inside the broker image. Nothing writes to
# `metric_sample`, `queue_snapshot` or any other table directly — a chart of
# invented samples tells you nothing about whether the product works, and it is
# the one thing a screenshot must not contain.
#
# The shape it builds, on purpose:
#   - several business-looking addresses at different rates;
#   - one address whose consumer is deliberately absent, so depth climbs and the
#     ingress/egress lines diverge — the picture an operator actually comes for;
#   - a real dead-letter backlog, produced by messages that were rejected;
#   - one stopped node, so the topology has a genuine warning rather than a staged
#     one.
#
# Metrics need real time to exist: tier B samples every 15s, so a credible 15m
# chart needs about that long. TRAFFIC_MINUTES controls it.
set -euo pipefail

STUDIO=${STUDIO:-http://localhost:8080}
ADMIN_USER=${ADMIN_USER:-admin}
ADMIN_PASSWORD=${ADMIN_PASSWORD:?set ADMIN_PASSWORD to the password just dev-up printed}
TRAFFIC_MINUTES=${TRAFFIC_MINUTES:-20}
COMPOSE=${COMPOSE:?set COMPOSE to the docker compose invocation for the demo stack}

JAR=/var/lib/artemis-instance/bin/artemis
COOKIES=$(mktemp)
trap 'rm -f "$COOKIES"' EXIT

say() { printf '\n\033[1m→ %s\033[0m\n' "$*"; }

# Studio protects mutating calls with the double-submit CSRF cookie a browser
# sends automatically; curl has to echo it back by hand.
csrf() { awk '$6 == "XSRF-TOKEN" { print $7 }' "$COOKIES" | tail -1; }

api() {
  local method=$1 path=$2
  shift 2
  curl -sS -b "$COOKIES" -c "$COOKIES" -X "$method" "$STUDIO/api/v1$path" \
    -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(csrf)" "$@"
}

say "waiting for Studio to answer"
# `just demo` may have just recreated the container; the compose healthcheck gates
# the brokers, not Studio, so without this the first login races the startup and
# fails with a connection reset.
for _ in $(seq 1 60); do
  curl -fsS "$STUDIO/actuator/health" >/dev/null 2>&1 && break
  sleep 3
done

say "signing in to Studio"
# One unauthenticated GET to be issued the CSRF cookie the login POST must echo.
curl -sS -b "$COOKIES" -c "$COOKIES" "$STUDIO/api/v1/auth/me" >/dev/null || true

if ! api POST /auth/login -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASSWORD\"}" \
     | grep -q '"username"'; then
  echo "login failed — is the password the one 'just dev-up' printed?" >&2
  exit 1
fi

say "registering the demo cluster"
# Re-registered rather than reused, so a second run cannot inherit a half-built
# registration from the first.
existing=$(api GET /clusters | python3 -c 'import json,sys; print(next((c["id"] for c in json.load(sys.stdin) if c["name"]=="demo"), ""))')
if [ -n "$existing" ]; then
  echo "removing the previous registration: $existing"
  api DELETE "/clusters/$existing" >/dev/null
fi
# Every node is seeded. Discovery finds the other three from the first, but a node
# discovered through a cluster connection carries no management URL, so it cannot
# be scraped — it would render as an unreachable node in a cluster that is in fact
# healthy.
cluster=$(api POST /clusters -d '{
  "seedUrls": [
    "http://artemis-primary:8161/console/jolokia",
    "http://artemis-backup:8161/console/jolokia",
    "http://artemis-secondary:8161/console/jolokia",
    "http://artemis-secondary-backup:8161/console/jolokia"
  ],
  "name": "demo",
  "description": "Four-node replication estate — two live/backup pairs",
  "credentials": {"username": "artemis", "password": "artemis"},
  "coreCredentials": {"username": "artemis", "password": "artemis"}
}' | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
echo "registered: $cluster"

# Rates chosen so the four addresses are visibly different on one axis rather than
# one line and three flat ones.
produce() { # node address count sleep-ms
  $COMPOSE exec -T "$1" $JAR producer \
    --url tcp://localhost:61616 --user artemis --password artemis \
    --destination "queue://$2" --message-count "$3" --sleep "${4:-0}" \
    --message-size 512 >/dev/null 2>&1 || true
}

consume() { # node address count
  $COMPOSE exec -T "$1" $JAR consumer \
    --url tcp://localhost:61616 --user artemis --password artemis \
    --destination "queue://$2" --message-count "$3" --receive-timeout 2000 \
    --break-on-null >/dev/null 2>&1 || true
}

say "creating the addresses"
for a in ORDERS.inbound PAYMENTS.capture SHIPPING.events NOTIFICATIONS.email AUDIT.trail; do
  produce artemis-primary "$a" 1
  consume artemis-primary "$a" 1
done

say "driving traffic for ${TRAFFIC_MINUTES} minutes"
deadline=$(( $(date +%s) + TRAFFIC_MINUTES * 60 ))
while [ "$(date +%s)" -lt "$deadline" ]; do
  # Balanced: produced and consumed at roughly the same rate.
  produce artemis-primary ORDERS.inbound 400 2 &
  consume artemis-primary ORDERS.inbound 380 &
  produce artemis-secondary PAYMENTS.capture 200 5 &
  consume artemis-secondary PAYMENTS.capture 190 &
  produce artemis-primary SHIPPING.events 120 8 &
  consume artemis-primary SHIPPING.events 115 &

  # No consumer at all: depth climbs and the two throughput lines diverge, which
  # is the backlog signal the charts exist to show.
  produce artemis-secondary NOTIFICATIONS.email 150 6 &

  # Low, steady, never drained — a queue that is simply accumulating.
  produce artemis-primary AUDIT.trail 40 20 &
  wait
done

say "building a dead-letter backlog"
# Consumed and rolled back past the redelivery limit is how a message really
# reaches the DLQ; the CLI cannot do that, so these are produced onto the DLQ
# address directly. It is the same address the broker itself dead-letters onto.
produce artemis-primary DLQ 240 0
produce artemis-secondary DLQ 90 0

say "sending a few JSON orders, so the SQL Console has a body to search"
# The CLI's --message-size produces filler, and a body predicate over filler
# demonstrates nothing. These go through Studio's own send-message endpoint —
# the same audited path an operator uses — so the console's headline query,
# `body->>'orderId' = ...`, has something real to find.
for id in 4471 4472 4473 4474 4475 4476 4477 4478; do
  tenant=$([ $((id % 2)) -eq 0 ] && echo acme || echo globex)
  api POST "/clusters/$cluster/queues/ORDERS.inbound/messages" -d "{
    \"type\": 3,
    \"durable\": true,
    \"body\": \"{\\\"orderId\\\": \\\"$id\\\", \\\"tenant\\\": \\\"$tenant\\\", \\\"total\\\": $((id % 97 + 12)).50, \\\"currency\\\": \\\"EUR\\\"}\",
    \"headers\": {\"priority\": 9},
    \"properties\": {\"tenant\": \"$tenant\", \"orderId\": \"$id\"}
  }" >/dev/null || true
done

say "seeding governance: environments, a scoped role, and two operators"
# The administration screen is only legible with more than one account in it, and
# a scoped grant is the thing that is hard to picture from a description.
# Idempotent by name: unlike the cluster these are not torn down between runs,
# and a second run must not fail on the 409 the first one's rows now produce.
ensure() { # collection name-field name body -> id
  local path=$1 field=$2 name=$3 body=$4 found
  found=$(api GET "$path" | python3 -c "import json,sys; print(next((r['id'] for r in json.load(sys.stdin) if r['$field']=='$name'), ''))")
  if [ -n "$found" ]; then echo "$found"; return; fi
  api POST "$path" -d "$body" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])'
}

env_prod=$(ensure /environments name production '{"name": "production", "sortOrder": 1}')
ensure /environments name staging '{"name": "staging", "sortOrder": 2}' >/dev/null

reader=$(ensure /roles name READER '{
  "name": "READER",
  "permissions": ["cluster:read", "queue:read", "message:browse", "metric:read"]
}')
operator=$(ensure /roles name OPERATOR '{
  "name": "OPERATOR",
  "permissions": ["cluster:read", "queue:read", "queue:pause", "message:browse", "message:move", "metric:read"]
}')

# A random password per run, printed nowhere: these accounts exist to populate the
# administration screen, not to be logged into.
new_user() { # username -> id
  ensure /users username "$1" "{\"username\": \"$1\", \"email\": \"$1@example.com\", \"password\": \"$(head -c 18 /dev/urandom | base64 | tr -d '=+/')\"}"
}
oncall=$(new_user sre-oncall)
analyst=$(new_user support-analyst)

# Re-granting an existing grant is a conflict, not a failure of the run.
api POST "/users/$oncall/grants" -d "{\"roleId\": \"$operator\", \"scopeType\": \"ENVIRONMENT\", \"scopeId\": \"$env_prod\"}" >/dev/null || true
api POST "/users/$analyst/grants" -d "{\"roleId\": \"$reader\", \"scopeType\": \"CLUSTER\", \"scopeId\": \"$cluster\"}" >/dev/null || true

say "stopping one node, so the topology carries a real warning"
$COMPOSE stop artemis-secondary-backup

say "done. Studio: $STUDIO — cluster $cluster"
