# ADR-0191: Audit export is streamed, checked like the audit view, and audited

- **Status**: accepted
- **Date**: 2026-10-13
- **Deciders**: Mahdi Amirabdollahi

## Context

Auditors need the audit trail as a file. Exporting can leak more than the screen shows, can exhaust
memory on millions of rows, and can turn into a spreadsheet formula attack through a crafted value.

## Decision

1. **One endpoint per cluster**, `GET /clusters/{id}/audit/export?format=csv|json|ndjson`, with the
   audit view's filters, behind a new cluster-scoped permission `audit:export`.
2. **The exporter sees exactly what the audit view shows them**: the same visibility rule and the same
   redaction, from the same code.
3. **Streamed**: rows are read through a cursor, oldest first, and written straight to the response.
   The web page links to the endpoint with `download`, so the browser writes to disk and its own
   cancel ends the request.
4. **CSV through Jackson's CSV dataformat**, and a cell starting with `=`, `+`, `-`, `@`, a tab or a
   carriage return gets a leading `'` so a spreadsheet shows it as text.
5. **Audited**: an `EXPORT_AUDIT` event names the format and the filters, and ends with the row count
   or "cancelled after N rows".

## Consequences

- An export of the whole trail costs a read cursor, not memory.
- A spreadsheet shows a neutralised value with its leading quote; JSON and NDJSON carry values as
  they are.
