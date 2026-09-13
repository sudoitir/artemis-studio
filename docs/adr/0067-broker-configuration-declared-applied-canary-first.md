# ADR-0067: Broker configuration is declared in Studio and applied canary-first over the management API

- **Status**: accepted
- **Date**: 2026-09-11
- **Deciders**: Artemis Studio maintainers

## Context

Studio reads a node's address settings and security settings (config diff, DLQ, the
capability probe) and creates diverts, addresses and queues one at a time. For every
other configuration change it hands the operator a `broker.xml` snippet. That is honest
(non-negotiable #5) but it leaves three gaps:

- a cluster's address settings, security settings and diverts have **no declared shape**
  anywhere in Studio, so the only comparison available is node-to-node
  (ADR-0043), which cannot see a cluster that is uniformly wrong;
- applying one setting to N nodes is N hand operations with no record of intent, so nodes
  drift from each other over time;
- runtime configuration **persists across restarts** (ADR-0065), so the real hazard is
  silent drift between the running broker and whatever manages `broker.xml` — in both
  directions — and Studio only warns about it.

Change 05 (`desired-state-drift`) proposed declaring queues and addresses and reporting
drift, advisory only, and deferred settings and diverts "until the shape earns it". It
referenced an "ADR-0053 — desired state is advisory" that was never written; the number
was taken by clock discipline. This ADR records that decision as well.

Six facts were measured against the dev pair (Artemis 2.44.0, the newest published image)
before this design was fixed — `docs/broker-management-notes.md` §15. The ones that shape
the decision: `addAddressSettings` **replaces** the entry for a match rather than merging
into it; the broker **silently ignores unknown JSON keys**; a second `createDivert` for an
existing name **succeeds and changes nothing**; and a setting applied on the primary is
present on the backup **once it becomes active**, not before.

The operator's constraint, stated during design: whatever this does, it must not break
the broker.

## Decision

**D1 — The declaration is the model; XML is an interchange format, not an editor.** A
cluster has one declaration: a typed document with `addresses` (and their queues),
`addressSettings`, `securitySettings` and `diverts`. The form editor edits the document.
*Import XML* parses a pasted `broker.xml` or fragment into the document and previews what
was recognised, what changed, and — element by element — what is **unsupported and will
not be applied**; nothing is dropped silently. *Export XML* renders the document as a
`<core>` fragment through an escaping writer. A free-form XML editor whose text is
applied directly is rejected: Studio would be compiling arbitrary XML to management calls,
errors could not be mapped to a field, and XML can express what the API cannot apply.

**D2 — Two apply modes per cluster, both always visible.** `STUDIO_MANAGED`: the primary
action is *Preview & apply*. `CONFIG_MANAGED`: the primary action is *Copy broker.xml
fragment* and the apply control is disabled **with the reason** — never hidden. Drift is
evaluated in both modes, so a config-managed cluster learns when its deployed `broker.xml`
matches the declaration.

**D3 — Apply is plan-driven and diff-driven.** A dry run reads every live node once
(batched, rate-limited) and produces, per node, the ordered steps whose observed state
differs from the declaration. A step whose observed state already matches is `ALREADY`
and issues no write. Order within a node: addresses → queues → address settings →
security settings → diverts; removals after additions, in reverse order.

**D4 — Canary, verify, then halt.** The canary (the first live node by name, or the one the
operator picks) receives every step; Studio re-reads it in at most two batched
requests and verifies each step; only then does the next node begin. A failed step anywhere stops the
run: remaining steps on that node and every remaining node report `NOT_ATTEMPTED`.
Nothing is rolled back (ADR-0049 D3 stands) and nothing beyond the failed node is
touched. This departs from ADR-0049's full fan-out, which is why configuration apply is
its own engine and not a new `LifecycleKind`. Re-running converges: matching steps are
`ALREADY`, the rest are attempted again.

**D5 — Replace semantics are disclosed.** Because the broker replaces the entry, the plan
shows, per match and per node, before → after for every key the broker reports and flags
a key that will change although the declaration does not set it as an `UNINTENDED_KEY_CHANGE`
hazard. Adopting a declaration from a cluster seeds each match with every reported key,
so declarations are full entries unless the operator trims them.

**D6 — Studio removes only what Studio applied, and never destroys a queue or an
address.** Every item Studio applies is recorded as Studio-owned. Removing an item from
the declaration produces a remove step only for an owned item. An undeclared item is
reported; removing it is an explicit per-plan opt-in with its own hazard, because
`removeAddressSettings` on a match that `broker.xml` also declares yields a state that
silently reverts on the next restart and Studio cannot tell the two apart (ADR-0065 D2).
Queues and addresses are never removed by an apply: destroying data goes through the
queue's own delete flow and its bulk cap. `config:apply` is a create-and-update authority.

**D7 — Hazards are classified before any write; High ones need acknowledgement and typed
confirmation.** The plan carries named hazards (message-loss policies, a limit below
current usage, a security match covering the management address, a `#` match, an
exclusive divert, replacing a divert, and the rest — the capability spec lists them). A
real run must echo every High hazard id; the UI arms the run by typing the cluster name;
the MCP tool takes the same list and a `confirm` string.

**D8 — Drift is advisory. Evaluation is scheduled; action never is.** A settings-registry
interval drives one bounded, rate-limited batched read (at most two POSTs) per live node
under the cluster lock and
records per-node state. No timer, rule or event calls the apply engine. Drift is an
alertable cluster-state condition.

**D9 — Studio never writes `broker.xml` and never calls `reloadConfigurationFile`.** The
config-managed path ends at "copy the fragment, deploy it with your own tooling, the next
evaluation shows the drift closed". Reload re-reads the whole file on the broker's disk,
including edits Studio has never seen — the one action that can break a broker for reasons
no preview can show.

**D10 — One catalogue knows the keys.** `AddressSettingKey` holds, per key, the XML name,
the JSON name, the type, the allowed values and the hazard class. Import, export, the
form, validation, the plan and drift all read it. An unknown key is a validation error,
because the broker would accept it and do nothing.

**D11 — MCP exposes apply, guarded like everything else.** One read tool, one mutating
tool; the mutating tool dry-runs by default, returns the plan and the acknowledgement ids
it needs, and acts only with `confirm` and those ids. Capture is not creatable over MCP
because its disclosure is for a human at the interface; configuration apply's disclosure
is a plan, which a tool result carries in full.

**D12 — Edits are versioned and audited; concurrent work is refused, not merged.** Every
save is a new revision; a save names the revision it edited and is refused when that is
no longer current. An apply names the plan it previewed and is refused when the plan has
changed underneath it. One apply per cluster at a time, under the cluster lock.

Static `<core>` settings — `global-max-size`, `persist-delivery-count-before-delivery`,
`<ha-policy>` — are **out of scope**: no version of the management API can apply them.
Import lists them as unsupported; nothing is stored for them.

## Consequences

Good: the cluster has a declared shape and every live node is measured against it, which
catches the uniformly wrong cluster node-to-node comparison cannot. Applying to N nodes is
one previewed, confirmed, audited command whose per-node × per-step outcome is readable
after the fact. The broker.xml snippet becomes an export of a real model instead of a
string assembled per feature.

Good: the safety story is stronger than the lifecycle fan-out's, in exactly the place an
operator is most exposed — a change that would have taken production traffic away is
named before it is armed, and a bad node stops the run instead of being one of three.

Bad: canary-then-halt is slower than fan-out and can leave a cluster one node ahead. That
is deliberate — the alternative leaves it N nodes wrong — and a re-run converges.

Bad: replace semantics push operators toward full entries. Adopt seeds them, but an
operator who declares three keys on a match `broker.xml` sets ten on will see seven
hazards and must decide. There is no way to make that decision for them honestly.

Bad: ownership is Studio's own record. A divert or setting created by another tool, or by
Studio before this change, is not owned and will only ever be reported, never removed
without the explicit opt-in.

Bad: the catalogue is version-specific. It was measured on 2.44.0 and is recorded as
such; a key added by a later broker is unknown to Studio until the catalogue learns it,
and is rejected — loudly — rather than passed through to a broker that would ignore it.

Change 05 is absorbed and its folder removed; its requirements live in the
`broker-configuration` capability, widened to settings and diverts.

## Alternatives considered

- **A free-form XML editor applied directly.** Rejected (D1).
- **`reloadConfigurationFile` after the operator edits `broker.xml`.** Rejected (D9).
- **Full fan-out like queue lifecycle (ADR-0049).** Rejected for configuration: a setting
  wrong on one node is a partial outcome the operator can see; a setting that breaks
  producers on every node at once is an outage. Canary-first costs a round trip and buys
  a bounded blast radius.
- **A `LifecycleKind` per configuration kind.** Would inherit the fan-out and the bulk
  cap; neither fits. The engine borrows the outcome vocabulary and the audit shape.
- **Automatic reconciliation of drift.** Rejected, permanently, for the reasons change 05
  gave: unbounded automatic blast radius, every safety mechanism assumes a human, and it
  changes what the product is.
- **Storing and verifying static settings without applying them.** Deferred as a
  roadmap item; the operator chose to keep this change to what the API can apply.
- **Keeping change 05 separate.** Two declaration models and two drift reports that must
  be cross-linked. One model, one report.
