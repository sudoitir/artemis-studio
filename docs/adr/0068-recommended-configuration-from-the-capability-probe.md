# ADR-0068: Recommended configuration is derived from the capability probe and applied on the operator's word

- **Status**: accepted
- **Date**: 2026-09-12
- **Deciders**: Artemis Studio maintainers
- **Extends**: [ADR-0067](0067-broker-configuration-declared-applied-canary-first.md),
  [ADR-0049](0049-cluster-wide-topology-mutation.md),
  [ADR-0065](0065-runtime-broker-configuration-persists-across-restarts.md)

## Context

Non-negotiable #5 says a capability Studio cannot use is stated with the exact
`broker.xml` that would enable it. That rule has been honoured since Phase 1, and
it left every capability gap as a dead end: the ledger told the operator precisely
what to write, and then made them go and write it, restart the broker, and come
back. On a first launch — the moment an operator decides whether the product is
worth their time — the whole screen is dead ends.

Three of those fragments are not static configuration at all. They are address
settings and security settings, which the management API accepts at runtime and
which ADR-0065 measured as surviving a restart. Studio already declares, plans,
applies and verifies exactly those two sections (ADR-0067). It was telling the
operator to do by hand something it had built an entire engine to do.

The rest genuinely cannot be applied. `broker-plugins`, `acceptors` and the
management security setting have no management operation behind them; the last one
additionally needs `artemis-roles.properties`, and a connection that cannot write
could not apply it anyway.

Two facts make this delicate rather than obvious:

- `addAddressSettings` **replaces** the whole entry for a match rather than merging
  into it (`docs/broker-management-notes.md` §15 M2). A recommendation carrying only
  the key it wants to change would silently reset every other key on that match.
- A recommendation that cannot be observed as done is a recommendation that never
  goes away. The probe reported message I/O as truncating from a fixed sentence and
  slow-consumer detection as permanently unknowable, so both would have reappeared
  after every successful apply.

## Decision

We will derive **recommendations** from the capability probe, and declare them —
never apply them — on the operator's word.

1. **Appliable is a property of the gap, and the rest are named.** A gap that is an
   address setting or a security setting is appliable; `broker-plugins`, `acceptors`
   and the management security setting never are, and are returned with their
   fragment and the reason rather than omitted.
2. **Every appliable recommendation carries the whole entry**, seeded from what the
   node currently resolves for that match, with its own keys on top. Replace
   semantics then change exactly what the screen says they change. When no node can
   be read, the entry is unseeded and the result says so, rather than presenting an
   unseeded entry as complete.
3. **Declaring saves an ordinary revision**, with source `RECOMMENDED` so the audit
   trail distinguishes it from an edit, an import and an adoption. Nothing reaches a
   broker. Applying it is the ordinary apply: plan, hazards, canary, typed
   confirmation. ADR-0067 D8 is untouched — the operator is still the one who acts,
   and the registration wizard's "register, then close the gaps" is two operator
   actions, not one automatic one.
4. **Recommended security-setting roles are prefilled from the broker** — whoever
   holds `consume` on that address today, falling back to the catch-all — and are
   editable before declaring. A block naming no role is refused: it applies cleanly
   and grants nobody anything.
5. **A capability is read back, not asserted.** The probe reads the catch-all
   address setting once and settles two questions from it: whether
   `management-message-attribute-size-limit` still caps returned bodies, and whether
   a `slowConsumerThreshold` is set. Both were previously fixed sentences. An absent
   threshold now means "none is configured" rather than "Studio cannot tell",
   because the broker echoes the key once it is set — measured on 2.44.0 and
   recorded as §16 M8, correcting the slice-0 reading that produced the old text.

## Consequences

A first launch now ends with the operator one confirmed action away from a broker
that can do what the product promises, without opening an editor. The ledger's
links go somewhere that acts instead of somewhere that copies.

What becomes harder: a recommendation is only honest while the probe can observe
its effect. Adding a fourth one means finding the read that proves it done, or
accepting a row that never clears — which is the failure mode this ADR exists to
avoid. The `view`/`edit` permission types are the standing example: §15 M7 shows
the broker accepts them and reports them back as `false`, so nothing about them can
ever be recommended.

The slow-consumer change trades one honesty for another. A broker old enough not to
echo the threshold at all will now be reported as having detection off when it is
on. That is stated in the reason text. It is the better trade: the previous
behaviour left a permanently unanswerable row on every cluster.

`managementMessageAttributeSizeLimit` now validates like the other unlimited-taking
keys (`-1` accepted). It previously rejected the one value its own snippet told
operators to paste, which made the recommendation undeclarable — found by the live
end-to-end run, not by a unit test.

## Alternatives considered

**Apply the recommendations as part of registration, in one action.** The plan's
original shape. Rejected: it duplicates the plan, hazard and confirmation UI inside
the wizard, and a combined action is exactly the surprise D8 forbids. Registering
now *navigates* to the recommendations, which is one click away and still the
operator's decision.

**Hand the raw `broker.xml` snippet to the XML importer.** What the ledger did
before. Rejected: a pasted fragment cannot be seeded from the node, so importing
it and applying it would reset every other key on the match — the failure mode M2
describes, reached by following the product's own link.

**Let Studio apply recommendations automatically on registration.** Rejected for
the same reason auto-adoption is rejected: a product that writes to a broker
nobody asked it to write to is not one an operator can trust with a cluster.
