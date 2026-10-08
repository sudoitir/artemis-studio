## Why

Destructive, settings and access-control changes take effect on the first click. Teams that need a
second person to approve such a change had no way to require it, and settings were one long list
edited key by key.

## What Changes

- An approval gate in front of every destructive, bulk, settings and access-control operation. With
  no approval provider installed nothing changes; an installed provider plugin may allow, deny or
  hold an operation, and a held operation runs once, exactly as requested, after a different person
  approves it (ADR-0179..0185).
- An in-app inbox with a header bell and a per-user stream, and notices sent through the configured
  alert channels.
- Settings applied as one change set, edited by category on a tabbed page, and grouped
  administration navigation.

## Capabilities

### New Capabilities

- `approval-gate`: holding, deciding and replaying gated operations.
- `notification-inbox`: per-user in-app notices.

### Modified Capabilities

- `studio-settings`, `operator-ui`, `alerting`, `plugin-runtime`.

## Impact

Plugin contract version 12. `PUT`/`DELETE /api/v1/settings/{key}` are replaced by
`POST /api/v1/settings/changes`.
