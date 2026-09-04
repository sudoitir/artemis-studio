# ADR-0021: Dry-run is a broker-side estimate; the bulk safety cap is server-enforced

- **Status**: accepted
- **Date**: 2026-09-04
- **Deciders**: Mahdi Amirabdollahi

## Context

Non-negotiable #2: "Every destructive operation takes `?dryRun=true` and returns
the affected count without acting. Purge/delete need typed confirmation in the
UI." The README Phase 3 checklist adds "Bulk actions with a safety cap and
preview."

Artemis has no dry-run mode. A management operation either acts or it does not.
So the affected count for a dry run has to be computed some other way, and it
cannot be exact for a by-filter operation on a live queue — messages arrive and
leave between the count and any subsequent act.

The cap needs a home. `studio_setting` already stores the scrape cadences, the
rate-limiter ceiling, and the metric retention window, seeded from
`ArtemisStudioProperties` and overridable at runtime through `GET/PUT
/api/v1/settings`. A cap enforced only in the browser is not a cap — a scripted
`curl` against the API would walk straight past it.

## Decision

- **Dry-run returns a broker-side estimate, clearly labelled.**
  - by filter → `countMessages(filterString)` on the queue MBean
  - by ids → the number of ids supplied
  - purge → the queue's `MessageCount`
  - send → `1`

  The API response and the UI both label the number a point-in-time estimate.
  The audit row for an executed operation records the broker's *actual* affected
  count, which is the number of record.

- **A dry run is audited.** It writes an `audit_event` with `dry_run = true` and
  `outcome = SUCCESS`. The column exists for exactly this, and "who probed the
  blast radius of what, and when" is worth keeping.

- **The bulk cap lives in `studio_setting` as `safety.bulk-cap`, default 1000.**
  `SettingsService.bulkCap()` reads the stored override else the
  `ArtemisStudioProperties` default; non-positive values are rejected by the same
  validation as the other settings. It appears in the `GET /api/v1/settings`
  effective-values response.

- **The cap is enforced in the service, before the act.** Every by-filter and
  purge mutation runs its count first. If `count > bulkCap()` and the request
  does not carry `?override=true`, the service throws
  `BulkCapExceededException(count, cap)`, which `ApiExceptionHandler` maps to
  `422` with `type: .../problems/bulk-cap-exceeded` and properties
  `affectedCount` and `cap`. By-id operations are capped on the id-list length
  the same way.

- **The UI reaches `override=true` only through the preview.**
  `BulkActionPreview` shows the dry-run count and the cap; when the count exceeds
  the cap it requires the queue name to be typed (`ConfirmByTyping`) before a
  "Run anyway" button resends the request with `override=true`. Purge always
  requires the typed confirmation regardless of count.

## Consequences

- Safe-by-default is real at the API boundary, not just in the UI. A scripted
  bulk delete of a whole queue fails closed with a number and a cap in the
  response.
- The dry-run count can disagree with the executed count when the queue is
  moving. This is inherent and is stated in the API and UI. The audit trail
  records both the estimate (dry-run row) and the truth (execution row).
- One extra Jolokia `countMessages` call per by-filter dry run and per by-filter
  execution's pre-flight. It is one call, behind the per-node rate limiter, and
  far cheaper than browsing to count.
- An operator with a legitimate large operation (a 50k-message DLQ purge) is not
  stuck — they get the preview, type the queue name, and override. The friction
  is proportional and audited.
- `safety.bulk-cap` is one more runtime setting to document and test alongside
  the cadences.

## Alternatives considered

- **Browse-and-count the exact matching ids for the preview.** Rejected — pages
  a `browse(filter)` across a potentially deep queue, hits the
  attribute-size-limit truncation, and loads message bodies Studio does not need.
  Worse for the broker (non-negotiable #1) to get a number that is still stale by
  the time the operator clicks.
- **A hard cap with no override.** Rejected — a real operational need (purging a
  large dead-letter queue after an incident) becomes impossible, pushing the
  operator to the Hawtio console and out of the audit trail.
- **Advisory cap, UI-only.** Rejected — the cap has to be server-side to mean
  anything; a direct API call must hit it too.
- **Cap as a compile-time constant.** Rejected — different estates have
  different notions of "too big"; it belongs with the other operational settings
  an operator tunes without a redeploy.
- **A separate audit action for dry runs vs executions.** Rejected — same action,
  distinguished by the `dry_run` boolean that already exists. Two action names
  for one intent makes the audit filter worse.
