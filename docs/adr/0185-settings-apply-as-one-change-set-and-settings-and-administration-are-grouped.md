# ADR-0185: Settings apply as one change set, and Settings and Administration are grouped

- **Status**: accepted. The tab widgets and the reason field are amended by [ADR-0188](0188-section-navigation-is-one-component-and-pages-outside-a-cluster-have-no-sidebar.md) and [ADR-0187](0187-the-approval-gate-keeps-two-approvers-and-asks-for-no-reason.md).
- **Date**: 2026-10-07
- **Deciders**: Mahdi Amirabdollahi

## Context

Operational settings ([ADR-0047](0047-two-configuration-planes.md),
[ADR-0138](0138-boolean-setting-kind.md)) are saved one key at a time, and the Settings page shows
"Operational configuration" as one table of every module's settings. With an approval gate
([ADR-0179](0179-an-approval-gate-in-the-service-layer-asks-one-provider.md)), a change that needs
several keys would become several requests, each approved alone, with nothing tying them together
and intermediate states running between approvals. The Administration page shows about ten
horizontal tabs, and approvals add more. Studio is laid out for desktop windows
([ADR-0164](0164-the-console-is-laid-out-for-desktop-windows-and-zoom.md)) and builds pages from
shared parts ([ADR-0163](0163-pages-are-built-from-shared-page-parts.md)).

## Decision

1. **A settings change is one change set.** `settings.apply` takes every changed key at once and is
   the single gated settings operation; putting or resetting one key wraps it. Its `stateKey` is a
   hash of the current values of those keys, so an approved change set refuses to run over values
   that changed meanwhile. `POST /settings/changes/preview` says whether it would be allowed or
   held.
2. **Each setting carries its category, category title, default value and pending change,** so the
   page can group, reset and show waiting requests without another call.
3. **Settings shows one tab per contribution, each a real form.** Inputs match each setting's kind;
   search covers key, label, hint and category, with a "Modified only" toggle, both in the URL; a
   modified setting offers a reset to its default. One draft spans every tab, marks the tabs it
   changed, and guards leaving with unsaved changes. A sticky footer applies the changes, or, when
   the preview says hold, requests approval through a review dialog with a diff, the policy and a
   reason. Pending changes show inline with who, when and Cancel.
4. **Administration uses grouped vertical navigation:** Access, Installation, Governance and
   Support, declared on each admin tab, so a plugin's tab joins a group.

## Consequences

- One approval covers one coherent change, and a change set never half-applies through the gate.
- `SettingsService` becomes a facade over a transactional writer, because the gate runs outside
  any transaction.
- The settings API grows metadata, and the Settings page is rewritten around a shared draft.
- Every admin tab, Studio's and a plugin's, names one of the groups.

## Alternatives considered

- **Gate each key separately.** Rejected: related keys would be approved and applied one by one,
  with mixed states between.
- **Keep one table and add a request button per row.** Rejected: it cannot show a reviewable diff
  across settings.
- **More horizontal tabs on Administration.** Rejected: they already overflow at ten.
