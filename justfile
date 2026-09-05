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

# Dev stack: Postgres + Artemis primary/backup + Studio. Builds Studio first.
[group('stack')]
up:
    {{compose_dev}} up --build -d
    @echo "→ http://localhost:8080   (Artemis console: http://localhost:8161)"
    @echo "→ waiting for Studio to be ready…"
    @timeout 90 bash -c 'until {{compose_dev}} logs studio 2>/dev/null | grep -q "Started ArtemisStudioApplication\|Created administrator"; do sleep 2; done' || true
    @{{compose_dev}} logs studio 2>/dev/null | grep -A4 'Created administrator' \
        || echo "→ admin account already exists (credentials were shown on first boot; reset with 'just down' then 'just up')"

# Stop the dev stack and delete its volumes.
[group('stack')]
down:
    {{compose_dev}} down -v

# Tail logs from the dev stack (all services, or `just logs studio`).
[group('stack')]
logs *service:
    {{compose_dev}} logs -f {{service}}

# Show dev stack status.
[group('stack')]
ps:
    {{compose_dev}} ps

# Bring up the prod reference stack (needs deploy/compose/.env).
[group('stack')]
prod-up:
    {{compose_prod}} --env-file deploy/compose/.env up -d

# Stop the prod reference stack (keeps volumes).
[group('stack')]
prod-down:
    {{compose_prod}} down

# ── develop ──────────────────────────────────────────────────────────────────

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

# Build the container image.
[group('build')]
image tag="artemis-studio:dev":
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

# psql into the dev database.
[group('database')]
db-shell:
    {{compose_dev}} exec postgres psql -U artemis_studio -d artemis_studio
