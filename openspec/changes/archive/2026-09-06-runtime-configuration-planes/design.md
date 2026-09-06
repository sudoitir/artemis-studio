# Design notes

## D1 — Why the registry is not generic

The obvious shape is `SettingKey<T>` with `Function<Properties, T>` and
`Consumer<T>`. In Java that forces `Map<String, SettingKey<?>>` and an unchecked
cast at every use. Every value already round-trips through a `String` (the `jsonb`
column stores a JSON scalar; the API takes and returns strings), so the registry is
non-generic and `Kind` drives parsing and validation. The typed getters stay, doing
the parse at the edge. Less code, no casts, same capability.

## D2 — `apply` is a `Runnable`, not a `Consumer<String>`

An applier wants a typed value, and the typed getter already produces one. So
`apply` is `() -> limiter.setPermitsPerSecond(limiterPermits())` — it pulls through
the getter rather than being handed a raw string to re-parse. `applyRuntime()` is
then "run every applier", and boot and post-write use the identical path.

## D3 — Pulled vs pushed

A key needs an `apply` only when its consumer caches the value. Most do not, and
for those `apply` is `null` and the next read is already the new value.
`NodeCallLimiter`, the three reapers, `BrokerEventWriter`, `RrCorrelator` and
`BrokerClientFactory` hold theirs in `volatile` fields because they are read per
message or per permit; those get an applier. This is why the javadoc states the two
routes explicitly — the wrong choice is invisible until someone reports that a
setting "didn't do anything".

## D4 — Validate before audit

`put` is `@Transactional`. Auditing first and catching the validation failure to
call `audit.fail` looks more thorough and records nothing: the throw rolls the
transaction back, audit row included. Either validation happens first (nothing was
attempted, nothing to record) or the failure audit needs `REQUIRES_NEW`. A
malformed duration is input validation, not an event in the history of this
deployment's configuration, so validation goes first and `audit.fail` is not used
here at all. `SettingsServiceTest.aRejectedChangeWritesNoAuditRow` pins it so the
"more thorough" version cannot be reintroduced.

## D5 — Cron validation refuses rather than clamps

These crons drive bulk `DELETE`s and `CREATE`/`DROP TABLE`. A stray seconds field
turns a nightly trim into a per-second one. Clamping would hide the typo; refusing
surfaces it where it was typed. A malformed expression at fire time stops the task
rather than falling back to a default schedule — a reaper running on a cron nobody
chose is worse than one that visibly stops.

## D6 — Why `spring.factories`, not `@Component`

The bootstrap context is constructed before component scanning, so a
`@Component` locator would never be seen. Registration goes through
`META-INF/spring.factories` under
`org.springframework.cloud.bootstrap.BootstrapConfiguration`. The locator opens its
own `DriverManager` connection because no `DataSource` bean exists yet.

## D7 — The locator must never be fatal

Liquibase runs in the main context, so on a first start `studio_config_property`
does not exist when the locator runs — this is the normal path, not an edge case,
and it is visible in the test logs. A missing table, unreachable database or bad
credentials log a warning and contribute nothing. Refusing to start would let an
empty database brick the start that was about to create its schema.

## D8 — `/actuator/refresh` authorization is not SpEL

`WebExpressionAuthorizationManager("@perm.can(...)")` evaluates without a bean
resolver: the rule compiles, wires, starts, and throws `IllegalArgumentException`
on the first request. Caught by `EndpointProtectionTest` rather than in production.
The rule calls `PermissionResolver` directly through the filter-chain lambda.

## D9 — Precedence flags travel inside the property source

Spring Cloud inserts a bootstrap property source at the top of the stack by
default, above environment variables. Proven on a real boot: a
`studio_config_property` row for `server.port` beat `SERVER_PORT` in the
environment. That is the wrong way round for a deploy-time plane — a bad row could
not then be corrected from outside the database.

Setting `spring.cloud.config.override-system-properties: false` in `bootstrap.yml`
does **not** fix it, which the second boot also proved.
`PropertySourceBootstrapConfiguration` binds `PropertySourceBootstrapProperties`
from the *incoming remote source*, so the flags must be in the map the locator
returns. They are, with a comment saying why, and
`theSourceCarriesTheFlagsThatKeepItBelowTheEnvironment` pins them. Final verified
order: packaged defaults < `studio_config_property` < environment.

## D10 — The `Props` test helper

Adding a section to `ArtemisStudioProperties` broke nine unrelated tests that
construct it positionally with all-null-but-one. `support/Props` gives
`defaults()` and one factory per section actually needed, so the next section is
one edit here instead of a positional `null` appended to a dozen files that do not
care.

## D11 — Not done, on purpose

- **No seeding of either table.** Absent means default; seeding freezes defaults
  across upgrades.
- **No blanket `@RefreshScope`.** It would proxy ~18 hot-path singletons to solve
  what plane A solves better.
- **No `@EnableConfigServer`.** `{cipher}` needs only `spring-cloud-context`, and
  serving config to other applications was explicitly out of scope.
- **`events.coalesce-window-millis` and `rr.percentile-window` stay compile-time.**
  The first is read once by `TopicCoalescer` at construction; the second is a
  Micrometer `distributionStatisticExpiry` fixed when the `Timer` is registered, so
  a later change would silently not apply. A setting that does nothing is worse
  than no setting.
