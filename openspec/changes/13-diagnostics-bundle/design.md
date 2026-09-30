## Context

See proposal.md for the motivation. Here is what Studio has today:

- **Logs:** they go to stdout only. There is no log file, no buffer and no endpoint, so nothing can be read back.
- **Redaction:** `SecretRedactor` is the one redaction rule set (ADR-0133). `RedactingLogging` puts redacting `%msg`/`%ex` converters into Logback's default converter map, so any `PatternLayout` built inside Studio already masks credentials.
- **Health:** actuator health runs with the `studio` group (jobs, brokers, subscriptions, permissions, pluginTrust).
- **Actuator threaddump:** Spring Boot actuator ships `ThreadDumpEndpoint` (`textThreadDump()`); it is not exposed.
- **Settings:** the settings registry holds only numbers, durations, cron values and booleans. Secrets live in the vault and are never readable.
- **Audit:** `AuditService` action strings are free-form. Permissions are string constants declared in a module descriptor, and ADMIN holds `*`.
- **Downloads:** nothing in Studio streams a file download yet.

## Goals / Non-Goals

**Goals:**
- The bundle is built entirely in-process, and building it needs no network.
- The preview is the download: the zip is written from the exact snapshot the admin reviewed.
- Every section is redacted by `SecretRedactor`, and no section is built from message data.

**Non-Goals:**
- Persisting logs across restarts: `docker logs` keeps them, and the bundle says so.
- Personal-data scanning of log text.
- A bundle format for machines, or an import of bundles back into Studio.

## Decisions

1. **An in-memory log ring buffer.** A Logback appender (`LogRingBuffer`) keeps the last 5 000 lines. Each line is formatted when it is appended, with a `PatternLayout` whose `%msg`/`%ex` are the redacting converters, so a line is stored already redacted.
   - It is attached to the root logger at `ApplicationStartingEvent`, next to `RedactingLogging`. Boot's logging reset drops foreign appenders, so it is re-attached on `ApplicationEnvironmentPreparedEvent` after `LoggingApplicationListener` has run.
   - Alternative: `logging.file.name` and reading the file back. It was rejected: it writes to disk in a container that has no volume for it, it needs rotation, and a file written by a pattern the user overrides might not be redacted.
2. **A server-side snapshot with a single-use id.**
   - `POST /api/v1/admin/diagnostics/bundles` gathers every section, redacts it and caches it for 10 minutes (Caffeine, at most 32 entries), keyed by a random id and bound to the user who made it. It returns every section's content, size and redaction count.
   - `POST /api/v1/admin/diagnostics/bundles/{id}/download` with `{sections:[…]}` streams a zip of exactly those sections from that snapshot. It audits `CREATE_DIAGNOSTICS_BUNDLE` with the section keys.
   - An unknown id, an expired snapshot or another user's snapshot answers 404.
   - Alternative: building the zip in the browser from the preview JSON. That needs a zip library in the web bundle for a single use.
3. **Sections:**
   - `about`: Studio version, plugin contract, Java, OS, CPUs, memory, uptime, database product and version, and the registered clusters with their name, Artemis version and node count. Clusters are shown without URLs or users.
   - `settings`: effective Studio settings, plus the secret vault status without values.
   - `health`: the actuator `studio` group with its details.
   - `threads`: `ThreadDumpEndpoint.textThreadDump()`.
   - `plugins`: every built-in feature and plugin with version, status and signature verification.
   - `logs`: the ring buffer.
   - Each section is text: JSON pretty-printed, or plain text for threads and logs. `SecretRedactor.redact` runs on the final text, so a value that slipped past structured masking is still caught. The redaction count is the number of `[redacted]` markers.
4. **No message content, whatever the governance policy.** No section reads messages, and Studio logs no message bodies or property values. A test drives a send and a browse with a planted body and property, then asserts that neither appears in the bundle.
5. **Permission `diagnostics:bundle`** (global only, "Create support bundles"). ADMIN gets it through `*`; no role migration.
6. **Bug report.**
   - `GET /api/v1/diagnostics/summary` needs only authentication. It returns the `about` facts that matter for a bug report: Studio, contract, Java, OS, database, sign-in providers, and the plugins with their versions.
   - The browser builds the issue text and adds its own user agent. It offers Copy and "Open on GitHub", a plain link the user clicks (`issues/new?title=&body=`). When the URL would exceed 8 000 characters, the link opens with only the title and the body goes to the clipboard.
   - Studio itself never calls GitHub.
7. **Module `diagnostics`** is a `FEATURE` kind with its own web feature. It adds an admin tab "Diagnostics" (preview, trim, download) and a user-menu item "Report a bug".

## Risks / Trade-offs

- [A log line from a library that prints a secret in an unrecognized shape] → `SecretRedactor` runs a second time on the whole section text, the preview highlights every `[redacted]` so the admin can review what was masked, and the admin can drop the logs section.
- [The ring buffer holds up to 5 000 lines in memory, about 1–2 MB] → a fixed cap; lines longer than 8 KB are truncated.
- [A thread dump of a very busy JVM can be large] → it is shown in a scroll area; the zip is compressed.
- [The snapshot cache lives on one node] → a download on another replica answers 404 with "prepare again". Change 11 (high availability) may revisit this.

## Migration Plan

This change adds only; there is no migration. Rollback means removing the module.
