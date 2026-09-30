# ADR-0146: The support bundle is built in-process from a previewed snapshot

- **Status**: accepted
- **Date**: 2026-09-30
- **Deciders**: Artemis Studio maintainers

## Context

When something goes wrong, maintainers need the version, the environment, the logs, the health and the
settings, and users are rightly careful about what leaves their installation. Several constraints shape how
Studio can collect this:
- Studio logs to stdout only, so it cannot read its own logs back.
- An administrator must be able to see exactly what a bundle holds, and to leave sections out, before
  anything is downloaded.
- Airgapped installations must be able to collect a bundle too.
- A bundle must never carry a credential (ADR-0133) or message content.

## Decision

- **Logs in memory.** A Logback appender, `LogRingBuffer`, keeps the last 5 000 formatted lines in memory,
  each truncated at 8 KB and passed through `SecretRedactor` when it is appended. It is attached to the root
  logger once each application context is initialised (`LogCapture`, registered in `spring.factories`), so it
  survives Boot's logging reset and holds the startup lines. Earlier lines stay in the container log.
- **A snapshot is the unit.** `POST /api/v1/admin/diagnostics/bundles` gathers every section once: about,
  settings, health, plugins, threads and logs. Each section is final text, and `SecretRedactor` runs over it a
  second time, so a value that slipped past a section's own masking is caught. The snapshot is cached in memory
  for ten minutes, bound to the user who made it, and returned whole for the preview.
- **The download is written from that snapshot.** `POST …/bundles/{id}/download` names the sections to keep
  and streams a zip of exactly those, so the file matches what was reviewed even though the logs and thread
  dump have moved on. An unknown, expired or foreign snapshot answers 404. The download is audited as
  `CREATE_DIAGNOSTICS_BUNDLE` with the kept section keys.
- **No message content.** No section reads messages, whatever the governance policy.
- **Permission.** A global-only permission, `diagnostics:bundle`, guards both calls.
- **Bug reports.** They use `GET /api/v1/diagnostics/summary`, open to any signed-in user. The browser builds
  the issue text and opens GitHub only when the user clicks; Studio never calls out.

## Consequences

- A bundle holds only the logs since the last start, at most 5 000 lines. That is enough for most reports;
  longer history comes from `docker logs`.
- Up to 32 snapshots, each a few MB at most, are held in memory for ten minutes.
- With more than one Studio replica, a download must reach the replica that prepared the snapshot. Otherwise
  the user prepares the bundle again. High availability (roadmap change 11) may revisit this.
- A new section is one entry in `DiagnosticsService` plus its description in the web panel. Its content is
  redacted by construction.

## Alternatives considered

- **A log file read back** (`logging.file.name`). It writes to a container filesystem with no volume for it,
  needs rotation, and a user-overridden pattern could print unredacted text.
- **Building the zip in the browser from the preview.** It needs a zip library in the web bundle for one
  screen, and it moves the "only what was previewed" guarantee into client code.
- **Re-collecting at download time.** The download would no longer match the preview, which is the guarantee
  the spec asks for.
