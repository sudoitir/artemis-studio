# ADR-0047: Two configuration planes — `studio_setting` for operators, a bootstrap plane for deploy-time properties

- **Status**: accepted
- **Date**: 2026-09-06
- **Deciders**: maintainers

## Context

Studio's configuration was in three places with no stated rule about which was
which: `application.yml` defaults bound to `ArtemisStudioProperties`, environment
variables for the four things a container must be told, and `studio_setting` rows
for the seven keys the Settings screen exposed. Everything else — broker timeouts,
every `rr.*` and `alerting.*` value, every reaper cron — was frozen at startup, and
the only way to change one was to rebuild or redeploy.

The request that prompted this asked for "a config server so settings change from
the panel without a restart", pointing at Spring Cloud Config Server's JDBC
backend. Two things about that turned out to be wrong on inspection, and one turned
out to be already solved:

- **Already solved**: ADR-0025 made the scrape cadences live via a
  `SchedulingConfigurer`. `SettingsService` and the Settings screen still *claimed*
  a restart was needed, months after it stopped being true. The complaint was real;
  its cause was stale documentation, not a missing mechanism.
- **Wrong**: there is no Spring Cloud release train for Boot 4.1. The latest,
  `spring-cloud-dependencies:2025.1.3` (2026-08-20), pins
  `<spring-boot.version>4.0.8</spring-boot.version>`. Oakwood is the Boot 4.0 line.
- **Wrong**: a Config *Server* cannot serve this application its own configuration.
  `spring.config.import=configserver:http://localhost:8080` would have the process
  call a port it has not opened yet. And `@RefreshScope` + `/actuator/refresh`
  cannot re-arm a `@Scheduled` cadence, so the trigger-task conversion in ADR-0048
  is required either way — the config server would not have removed the work it was
  proposed to remove.

What Spring Cloud does bring that is genuinely missing is the **bootstrap phase**:
a context that exists before the main one, where a property source can be built
from the database and `{cipher}` values can be decrypted, both of which have to
happen before anything is bound.

The two needs are different in kind, and conflating them is what produced the
original muddle. An operator changing a scrape cadence wants it live now, audited,
and behind a permission. A deployment setting an OIDC issuer URL wants it fixed for
the life of the process and identical across restarts.

## Decision

**We will run two configuration planes, with a stated boundary.**

**Plane A — `studio_setting`, the operator plane.** A registry in `SettingsService`
is the single definition of every operator-tunable key: its group, label, hint,
kind, packaged default, and how it applies. Adding a setting is one `register(...)`
line, and the API, validation, reset affordance, audit row and Settings screen all
follow from it — the screen renders whatever the registry describes, so there is no
matching frontend change to forget. A stored row wins over the `application.yml`
default; deleting it restores the default.

- **Nothing is ever seeded.** An absent row *means* "use the packaged default",
  which is what lets an upgrade ship a new default instead of being pinned by a
  value written at first start. There is no first-run initialisation step.
- **Every key applies without a restart**, by one of three routes: pulled (the
  consumer asks `SettingsService` per use), pushed (the registry writes into a
  `volatile` field on a consumer that reads too often to look up), or a trigger
  task that re-reads the schedule on each fire (ADR-0048).
- **Writes are audited in the command's transaction** (non-negotiable #3).
  Validation runs *before* the audit row, because a rejected value never becomes a
  transaction and a `begin`/`fail` pair around it would roll back and record
  nothing.

**Plane B — `studio_config_property`, the deploy-time plane.** A
`PropertySourceLocator` registered into the Spring Cloud bootstrap context reads
`application`/`profile`/`label`/`key` rows over its own plain JDBC connection and
contributes them to the `Environment` before the main context starts. This is the
plane for what must be known while the application is starting, and therefore
cannot be changed from inside it.

- **`bootstrap.yml`** carries only what the locator needs to reach Postgres and the
  `encrypt.key` that decrypts `{cipher}` values. Precedence, lowest to highest:
  `bootstrap.yml` → `application.yml` → `studio_config_property` → environment. The
  environment deliberately keeps the last word: Spring Cloud's default puts a
  bootstrap source above everything, which would let a row beat the container's own
  settings and leave no way to correct it from outside the database. The three flags
  that reorder it (`allowOverride`, `overrideNone`, `overrideSystemProperties`) are
  emitted **by the locator into its own property source**, not written in
  `bootstrap.yml`, because `PropertySourceBootstrapConfiguration` binds them from the
  incoming remote source — in `bootstrap.yml` they are read by nobody and silently do
  nothing. This was established by running it both ways, not from the documentation.
- **The locator is never fatal.** A missing table, unreachable database or bad
  credentials log a warning and contribute nothing. Refusing to start would let an
  empty database brick the very start that was about to create its schema.
- **We will not add `spring-cloud-config-server`.** No `@EnableConfigServer`, no
  `/{app}/{profile}/{label}` endpoint. `{cipher}` decryption comes from
  `DecryptEnvironmentPostProcessor` in `spring-cloud-context`, which needs no server.
  Only `spring-cloud-starter-bootstrap` is added.
- **`/actuator/refresh`** is exposed and gated on `settings:write`, called through
  `PermissionResolver` directly rather than a SpEL `@perm.can(...)` expression —
  `WebExpressionAuthorizationManager` evaluates without a bean resolver, so the SpEL
  form fails at request time rather than at startup.

**The encryption boundary.** `encrypt.key`
(`ARTEMIS_STUDIO_CONFIG_ENCRYPT_KEY`) covers `Environment` property values only.
`SecretVault` (AES-GCM under `ARTEMIS_STUDIO_SECRET_KEY`, ADR-0009) remains the
sole owner of per-cluster broker credentials in `broker_credential`. Two keys, two
jobs, no migration path between them, and they must not be set to the same value.

**On the version gap.** Spring Cloud 2025.1.3 is built against Boot 4.0.8 and we
run 4.1.0. This was accepted only after being proven, not assumed: the BOM manages
zero `org.springframework.boot` artifacts (so it cannot silently downgrade Boot),
`spring-cloud-context:5.0.3` already compiles against Boot 4's relocated
`org.springframework.boot.bootstrap` package, and a spike confirmed the full
context starts, `bootstrap.yml` loads, and the refresh machinery wires up. If a
future Spring Cloud upgrade breaks on this, the recorded exit is a pure-Boot
`ConfigDataLocationResolver` behind `spring.config.import=studiodb:`, which
forfeits `bootstrap.yml` and `{cipher}` and keeps everything else.

## Consequences

- Adding an operator setting is one line and cannot drift from the UI, because
  there is no second list to update. The previous hint text claiming "takes effect
  on restart" is the exact failure this removes the possibility of.
- Every settings write is now audited, closing a standing violation of
  non-negotiable #3.
- `ArtemisStudioProperties` stops being the place to read a live value. It is the
  *defaults*; `SettingsService` is the effective value. A field is read straight off
  the record only where the registry says it has no key.
- **`/actuator/refresh` cannot rebind most of the application.**
  `ArtemisStudioProperties` is an immutable record injected into ~18 singletons that
  copy the reference. The rebinder re-creates the properties bean; those holders
  keep the old instance unless each is itself a `@RefreshScope` proxy. We
  deliberately do not blanket-annotate them. Refresh re-reads the `Environment` and
  helps beans that consult it lazily; `studio_setting` remains the mechanism for
  anything an operator changes. Anyone expecting refresh to be a general
  live-reconfiguration tool will be disappointed, and this is where they should
  find out.
- We now carry a Spring Cloud BOM on a train not built for our Boot minor. That is
  a real upgrade risk, priced deliberately and pinned by tests that boot the full
  context.
- Two tables that both hold configuration will invite "which one?" forever. The
  boundary is: can the application read it after it has started? Then plane A.
- Multi-instance HA (roadmap) needs plane A changes to propagate between replicas —
  today `applyRuntime()` is process-local. That joins the per-cluster advisory-lock
  work, not this change.

## Alternatives considered

- **Spring Cloud Config Server with the JDBC backend, as proposed.** Rejected on
  three counts above: the self-consumption problem, the Boot 4.1 gap, and that it
  does not solve live cadences anyway. It also adds a second definition of the same
  keys.
- **A separate config-server container.** Solves self-consumption; violates ADR-0007
  ("one container image") for a single-instance product with one database.
- **One plane — put everything in `studio_setting`.** Cannot work: the datasource
  URL and the decryption key are needed before the code that reads
  `studio_setting` exists.
- **Seed `studio_setting` on first start**, as the request asked. Rejected: seeded
  rows freeze the defaults of the version that happened to start first, so an
  upgrade shipping a better default would be silently ignored on every existing
  deployment. Absent-means-default is strictly better and needs no code.
- **Blanket `@RefreshScope` on the properties consumers.** Rejected: it would make
  ~18 hot-path singletons proxied to fix a case `studio_setting` already covers
  better, and proxying the scrape path to change a timeout is a poor trade.
