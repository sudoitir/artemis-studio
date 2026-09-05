# Artemis Studio — task runner.  Run `just` for the menu.

set shell := ["bash", "-uc"]
set dotenv-load := true

compose_dev  := "docker compose -f deploy/compose/compose.dev.yaml"
compose_prod := "docker compose -f deploy/compose/compose.prod.yaml"
mvn          := "./mvnw"
npm          := "npm --prefix web"

# ── default ──────────────────────────────────────────────────────────────────

# List all recipes, grouped.
default:
    @just --list --unsorted --list-heading $'Artemis Studio tasks\n'

# ── stack ────────────────────────────────────────────────────────────────────

# Run Artemis Studio + Postgres from the published image. Register your brokers in the UI.
[group('stack')]
up: setup
    {{compose_prod}} --env-file deploy/compose/.env up -d
    @echo "→ http://localhost:8080"
    @echo "→ waiting for Studio to be ready…"
    @timeout 120 bash -c 'until {{compose_prod}} --env-file deploy/compose/.env logs studio 2>/dev/null | grep -q "Started ArtemisStudioApplication\|Created administrator"; do sleep 2; done' || true
    @{{compose_prod}} --env-file deploy/compose/.env logs studio 2>/dev/null | grep -A4 'Created administrator' \
        || echo "→ admin account already exists (its password was shown on first boot)"

# Stop the stack (keeps the Postgres volume).
[group('stack')]
down:
    {{compose_prod}} --env-file deploy/compose/.env down

# Tail logs (all services, or `just logs studio`).
[group('stack')]
logs *service:
    {{compose_prod}} --env-file deploy/compose/.env logs -f {{service}}

# Show stack status.
[group('stack')]
ps:
    {{compose_prod}} --env-file deploy/compose/.env ps

# Write deploy/compose/.env (generated secrets on first run), pinned to the latest release tag.
[group('stack')]
setup:
    #!/usr/bin/env bash
    set -euo pipefail
    env=deploy/compose/.env
    if [ ! -f "$env" ]; then
        sed -e "s|^SECRET_KEY=.*|SECRET_KEY=$(openssl rand -base64 32)|" \
            -e "s|^DB_PASSWORD=.*|DB_PASSWORD=$(openssl rand -hex 24)|" \
            deploy/compose/.env.example > "$env"
        echo "→ wrote $env with generated secrets"
    fi
    # BSD- and GNU-portable: no `sed -i`, no `sort -V`.
    latest=$(git ls-remote --tags --refs origin 2>/dev/null | sed 's;.*refs/tags/;;' \
        | grep -E '^[0-9]{4}\.[0-9]{2}\.[0-9]+$' | sort -t. -k1,1n -k2,2n -k3,3n | tail -1 || true)
    if [ -n "$latest" ]; then
        tmp=$(mktemp)
        sed "s|^STUDIO_IMAGE=.*|STUDIO_IMAGE=sudoit1/artemis-studio:$latest|" "$env" > "$tmp"
        mv "$tmp" "$env"
        echo "→ pinned STUDIO_IMAGE to :$latest"
    else
        echo "→ no release tag found; STUDIO_IMAGE stays :dev"
    fi

# ── develop ──────────────────────────────────────────────────────────────────

# Full dev stack: Postgres + a real Artemis primary/backup pair + Studio, built locally.
[group('develop')]
dev-up:
    {{compose_dev}} up --build -d
    @echo "→ http://localhost:8080   (Artemis console: http://localhost:8161)"
    @echo "→ waiting for Studio to be ready…"
    @timeout 90 bash -c 'until {{compose_dev}} logs studio 2>/dev/null | grep -q "Started ArtemisStudioApplication\|Created administrator"; do sleep 2; done' || true
    @{{compose_dev}} logs studio 2>/dev/null | grep -A4 'Created administrator' \
        || echo "→ admin account already exists (reset with 'just dev-down' then 'just dev-up')"

# Stop the dev stack and delete its volumes.
[group('develop')]
dev-down:
    {{compose_dev}} down -v

# Tail dev stack logs (all services, or `just dev-logs studio`).
[group('develop')]
dev-logs *service:
    {{compose_dev}} logs -f {{service}}

# Show dev stack status.
[group('develop')]
dev-ps:
    {{compose_dev}} ps

# Run backend (:8080) and Vite (:5173) together; Ctrl-C stops both.
[group('develop')]
dev:
    #!/usr/bin/env bash
    set -uo pipefail
    trap 'kill 0' EXIT
    {{mvn}} spring-boot:run &
    {{npm}} run dev &
    wait

# Backend only, with live reload.
[group('develop')]
dev-api:
    {{mvn}} spring-boot:run

# Frontend only (expects the API on :8080).
[group('develop')]
dev-web:
    {{npm}} run dev

# ── build ────────────────────────────────────────────────────────────────────

# Full jar with the SPA baked in.
[group('build')]
build:
    {{mvn}} -Pfrontend clean package

# Build the container image locally.
[group('build')]
image tag="artemis-studio:local":
    docker build -t {{tag}} .

# ── quality ──────────────────────────────────────────────────────────────────

# Everything CI runs: backend verify + frontend build + lint.
[group('quality')]
verify: verify-api verify-web

# Backend: compile, migrate against Testcontainers Postgres, test. Needs Docker.
[group('quality')]
verify-api:
    {{mvn}} verify

# Frontend: type-check, build, lint, DOM tests.
[group('quality')]
verify-web:
    {{npm}} run build
    {{npm}} run lint
    {{npm}} test

# Format Java (Palantir, via Spotless) and the web sources.
[group('quality')]
fmt:
    {{mvn}} spotless:apply
    {{npm}} run lint -- --fix

# ── database ─────────────────────────────────────────────────────────────────

# Print the SQL the pending changesets would run.
[group('database')]
db-sql:
    {{mvn}} liquibase:updateSQL

# Show applied vs pending changesets.
[group('database')]
db-status:
    {{mvn}} liquibase:status

# Roll back the last N changesets (default 1).
[group('database')]
db-rollback count="1":
    {{mvn}} liquibase:rollback -Dliquibase.rollbackCount={{count}}

# psql into the dev-stack database.
[group('database')]
db-shell:
    {{compose_dev}} exec postgres psql -U artemis_studio -d artemis_studio
