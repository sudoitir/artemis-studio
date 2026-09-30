# ADR-0138: Operational settings gain an on/off kind

- **Status**: accepted
- **Date**: 2026-09-30
- **Deciders**: Mahdi Amirabdollahi

## Context

Operational settings (ADR-0047) had three kinds: duration, integer and cron. The code said a
fourth would be a design decision, because each kind is an input on the settings screen. The
installation-wide MCP read-only mode (ADR-0137) is a switch, and a switch encoded as an integer
(`0`/`1`) or a duration misleads whoever edits it.

## Decision

We will add `SettingDef.Kind.BOOLEAN`, whose values are exactly `true` or `false`, read with
`SettingsService.bool(key)`. Operational configuration renders it as a switch that saves when
flipped, with a Reset while overridden. `SettingDef` is plugin API, so plugins can declare one too.

## Consequences

- Adding an enum constant is binary compatible. A plugin's exhaustive `switch` over `Kind` would
  need a new branch when rebuilt.
- The settings screen now has four inputs.

## Alternatives considered

- **An integer setting with `min = 0` and `max = 1`.** It reads as a number, and a wrong value is
  a typo rather than impossible.
