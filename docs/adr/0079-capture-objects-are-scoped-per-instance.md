# ADR-0079: Capture broker objects are scoped per Studio instance, and bounded in bytes

- **Status**: accepted
- **Date**: 2026-09-14
- **Deciders**: maintainers

## Context

Full capture (ADR-0062) installs, per node and source address:
- a divert;
- a non-durable capture queue;
- an address setting and a security setting on the match `artemis-studio.capture.#`.

Tap *names* already carry the Studio instance id, so an instance never removes another
instance's divert or queue. The *settings* did not: every instance wrote the same shared
match, and `CaptureTap.remove` deleted it once *this* instance had no taps left. A second
instance's capture queues then lost their DROP policy and their access restriction while
still draining production copies.

An audit of the capture flow found three more ways a tap could be unsafe, or record
nothing while looking healthy:

- **No byte bound.** The capture queue was bounded by message count only (up to
  10,000,000). With DROP applied only at the broker's global size, a ring of large messages
  was bounded in principle but not in practice.
- **Self-capture.** Nothing stopped a capture pattern resolving to Studio's own capture
  addresses. A pattern of `#` would tap its taps, and each reconciliation would grow the
  broker's objects.
- **Nominal restriction.** The broker role capture queues are restricted to defaulted to
  `amq`, a role every `artemis create` user holds.

Separately, the divert management API accepted any name, so a divert created or deleted
through REST or MCP under the capture prefix could collide with, or silently remove, a
capture tap. Only the UI prevented it.

## Decision

We will:

1. **Scope the settings per instance.** Capture settings use the match
   `artemis-studio.capture.<instanceId>.#`, and removing a tap only ever touches this
   instance's match. The legacy shared match is removed only when no capture divert from
   any instance remains on the node.
2. **Bound the capture queue in bytes.** A `maxSizeBytes` from
   `artemis-studio.capture.max-ring-bytes` (default 64 MiB) is set alongside the message
   bound, and the ring-size ceiling is lowered to 1,000,000.
3. **Reserve the capture prefix.**
   - Capture never resolves a pattern to an address under it, and a pattern that can only
     match it is refused.
   - The divert service refuses to create or delete a divert under it, whatever the
     interface.
4. **Require the broker role.** `artemis-studio.capture.broker-role` has no default. Until
   it is set, capture is refused with the setting named. A value of `amq` is accepted with
   a warning shown on the subscription.

## Consequences

- Two Studio instances can capture the same estate without either weakening the other's
  capture queues.
- A capture tap is bounded by count, bytes and age whether or not Studio is draining it.
- **Upgrade (breaking):** an estate that relied on the `amq` default must set the broker
  role; until then its captures report FAILED with the setting named. Existing ring sizes
  above 1,000,000 are clamped.
- A divert under the capture prefix can no longer be managed from the routing API. Capture
  subscriptions are the only way to affect one, as the routing spec already required of the
  UI.

## Alternatives considered

- **Keep the shared match, and remove it only when no instance has taps.** This needs every
  instance to see every other instance's taps before removing a setting. That is reachable
  through the divert list, but it re-creates coupling that per-instance scoping removes
  outright.
- **Keep a count-only ring and document typical message sizes.** A bound that depends on
  traffic someone else controls is not a bound.
- **Default the broker role to a dedicated `studio` role.** It would fail closed on every
  estate that has not created that role. An explicit requirement fails just as closed and
  says why.
