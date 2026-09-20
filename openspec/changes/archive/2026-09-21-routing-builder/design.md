## Context

See `proposal.md` — Why. The design-relevant state of the codebase:

- **The declaration engine already does almost all of this.** `feature/brokerconfig` carries
  `BrokerConfigDocument` (addresses, address settings, security settings, diverts) through
  `BrokerConfigValidator`, `BrokerConfigPlanner`, `BrokerConfigApplyService` (canary-first,
  `expectedPlanHash`, hazard acknowledgement), `BrokerConfigDriftService` and
  `BrokerXmlCodec`. `DivertDecl` already has `transformerClassName` and
  `transformerProperties`; `BrokerXmlCodec` already parses and writes
  `<transformer><class-name>` and its `<property key= value=>` children.
- **`ModeControl.tsx` already switches the configuration screen's modes**, and
  `EditorDrawer`, `KeyValueList`, `useSaveDocument`, `document.ts`, `ReviewApplyDrawer`,
  `ApplyTimeline`, `ConfigDiffView` and `XmlDrawers` are all already there and all already
  operate on the whole document against the revision it was read from.
- **The graph substrate is in the tree.** `@xyflow/react` 12.11.6 and `elkjs` 0.12.0 are
  dependencies. `features/flow/useFlowLayout.ts` holds `elkInstance()` and
  `runLayout(ElkNode)`, which are already generic — the flow-specific part is
  `flowLayout.ts` beside them. ADR-0080 says in as many words: *"A future graph elsewhere
  (routing builder, lineage) can reuse the same layout worker; it is not generalised now."*
- **Divert primitives exist in `feature/queues`**: `DivertOperations` with `divertConfig`,
  `cycle`, `createVerified` (read-back, returning `APPLIED`/`ALREADY`/`FAILED` naming the
  differing fields) and `listBridges`. Bridges are read (`BridgeRow`, `bridgesPattern`) and
  never written; nothing in the repo has ever called `createBridge`.
- **`ck_broker_config_owned_item_kind`** restricts `kind` to `ADDRESS_SETTING`,
  `SECURITY_SETTING`, `DIVERT` (`feature/brokerconfig/changes/0001-baseline.sql:66`).
- **`DivertEditor.tsx` drops transformer properties on the floor**: it renders the class name
  and writes back `transformerProperties: item?.transformerProperties ?? {}`.

## Goals / Non-Goals

**Goals:**

- One write path. The canvas produces a `ConfigDocumentView`; everything after that is the
  apply engine that already exists.
- Bridges reach parity with diverts as declared items, without a second command executor.
- Reuse over reimplementation: the ELK worker, the editors, the drawer, the diff, the apply
  timeline and the typed confirmation are all existing components.
- The graph never becomes a capability an operator can be locked out of.

**Non-Goals:**

- A live "apply this one element now" action on the canvas. It would be the second write path
  ADR-0071 exists to prevent, and ADR-0087's scoped apply already narrows an apply to one item.
- Cluster connections and acceptors. Different constructs, different failure modes; the
  management API cannot create an acceptor at all.
- A transformer catalogue. See Decision 6.
- Auto-layout persistence (remembered node positions). ELK is deterministic on the same graph;
  storing positions would add state that drifts from the declaration for no operator gain.

## Decisions

### 1. The builder is a mode of the configuration screen, not a screen of its own

ADR-0087 decided there is *one* configuration screen with an inline apply that can be scoped
to one item. A routing builder that applies configuration from somewhere else would be a
second apply surface with its own plan presentation, its own hazard acknowledgement and its
own opportunity to diverge. The canvas edits the declaration, so it belongs beside the
declaration.

Concretely: `ConfigurationView`'s `Tabs` gain a "Routing builder" tab — `ModeControl.tsx` is
the apply-mode popover, not the screen's view switch — and the canvas lives in
`web/src/features/brokerconfig/routing/`, with its anchor and selection in
`ConfigurationSearch`. The Declared tab is unchanged and remains the non-graph equivalent the
spec requires.

*Alternatives considered.* A canvas in `feature/routing` beside the divert and bridge tables —
rejected: it would need a new `routing → brokerconfig` edge in both `package-info.java` and
`web/eslint.config.js` (ADR-0074 requires both, deliberately), and it would put an editor of
the declaration outside the module that owns it. A new `feature/routing-builder` module —
rejected: it would re-declare brokerconfig's entire dependency set to edit brokerconfig's
document, for no isolation gain, and would split one concept across two toggles.

*Consequence.* No new backend module, no new frontend feature, no new eslint edge, no new nav
group, no new permission. The builder inherits `brokerconfig`'s feature toggle, which is the
correct blast radius: turning the configuration feature off turns the builder off with it.

### 2. Authoring is not previewing, and that is what reconciles the canvas with `operator-ui` #19

`operator-ui` requires that a previewed mutation is exactly what is submitted, that a
preview's inputs are not editable while it is shown, and that changing a value forces a new
preview. A canvas whose every drag were a mutation preview would fight this continuously.

The resolution is that the canvas is not a preview of anything. It authors a document. The
preview is what it already is: `ReviewApplyDrawer`, computed from the saved revision, with
frozen inputs and a `planHash` that is recomputed on the way into the confirmation so a
cluster that moved is reported as a new plan to review rather than as a refusal after the
operator has typed the cluster's name.

This is worth stating as an ADR because it is the load-bearing distinction: a future
contributor's instinct will be to make the canvas act on the broker, and the reason not to is
not obvious from the code.

*Consequence.* "Save as revision N+1" is the canvas's only commit, and it is
`useSaveDocument` unchanged — optimistic-concurrency against the revision the document was
read from, so two operators editing the same cluster collide loudly rather than silently.

### 3. Bridges are declared items, applied by the existing engine

A bridge becomes `BridgeDecl` in `BrokerConfigDocument`, validated by
`BrokerConfigValidator`, planned by `BrokerConfigPlanner`, applied by
`BrokerConfigApplyService`, compared by `BrokerConfigDriftService` and round-tripped by
`BrokerXmlCodec` — the same five places a divert already passes through. The per-node action
is `BridgeOperations.createVerified` / `destroy`, mirroring `DivertOperations`.

Ordering: addresses → queues → address settings → security settings → diverts → **bridges**,
removals in reverse. A bridge reads from a queue and forwards to an address; it must be last
in, first out. This extends ADR-0067 D3 rather than reinterpreting it.

Replacement: Artemis has no `updateBridge`, so a changed bridge is a destroy and a create,
planned and presented as exactly that — the same shape `DivertEditor` already warns about for
diverts, and the same reason `routing-management` keeps "changing a divert is an explicit
delete and create".

*Alternatives considered.* A live bridge API in `feature/routing` mirroring the divert routes
— rejected under Decision 1 and ADR-0071: it is a second write path, and it would recreate
precisely the silent cross-broker divergence the old prohibition was protecting against,
because nothing would record what the cluster is supposed to be.

*Consequence.* The `routing-management` prohibition is superseded (ADR-0091), and one
Liquibase changeset adds `BRIDGE` to `ck_broker_config_owned_item_kind` so the apply engine's
ownership tracking — which is what makes "remove only what we applied" true — covers bridges.

### 4. `createBridge` is measured before it is relied on

Nothing in this repository has ever called `createBridge`. ADR-0065 exists because a different
assumption about the management API, held by two features at once, turned out to be exactly
backwards when someone finally measured it in a minute against the dev stack.

So task 1 is the measurement, against the pinned 2.56 broker, recorded in ADR-0091 the way
ADR-0065 records its own: the operation's exact signature, what `BridgeControl` reports back
(which determines what `createVerified` can compare), and whether a management-created bridge
survives a restart.

*If the measurement fails* — no operation, or an unusable read-back — the change still ships:
bridges are declared, exported as `<bridge>` configuration, and diffed against what is
observed, with live apply refused and the XML shown. That is strictly more than today. ADR-0091
records the negative result and the prohibition is superseded only in part. This branch is
written down now so it is a decision at task 1, not a surprise at task 9.

*Outcome (task 1, 2026-09-20, broker 2.44.0).* The measurement passed and the full branch is
taken. `createBridge(java.lang.String)` takes a hyphenated broker.xml-style document;
a transformer is the nested `transformer-configuration: { class-name, properties }`; an
unknown key is accepted with a 200 and silently ignored, which makes the read-back mandatory
rather than defensive; `BridgeControl` reports thirteen of the twenty-three declarable fields;
a concurrency above one deploys `<name>-0 … <name>-(N-1)` while `destroyBridge` takes the bare
declared name; and everything, transformer properties included, survives a restart. The full
record is in `tasks.md` under *Notes from apply* and is reproduced in ADR-0091.

### 4a. Verification reports what it covered, and does not infer the rest

`BridgeControl` reports thirteen of the twenty-three fields `createBridge` accepts. Window
sizes, large-message size, check period, connection TTL, routing type, concurrency, client id,
initial connect attempts and the credential are all write-only from the management API's point
of view.

So `createVerified` compares what is reported and says so. It does not report a bridge as
matching on a field it cannot see, and drift claims nothing about those fields either — an
absence of evidence is not evidence of agreement, which is the same discipline ADR-0049 D5
applies to the write capability and ADR-0065 D2 applies to a divert's origin.

*Alternatives considered.* Treating an unreported field as matching — rejected: it is the
comfortable lie, and it would make an apply that silently dropped `confirmation-window-size`
look clean. Refusing to declare fields that cannot be verified — rejected: they are real
configuration an operator needs, and the export carries them correctly even when the live
read-back cannot.

*Consequence.* The per-node outcome and the drift report both distinguish "matches",
"differs" and "not reported by the broker". That third word is the honest one and it is new
vocabulary the apply result already has room for.

### 5. Bridge credentials are vaulted; the declaration holds a reference

A bridge authenticates to the broker it forwards to. That password would otherwise land in a
jsonb document, in every stored revision of it, in the diff between two revisions, in the
audit row's parameters, in the MCP tools' responses and in the exported XML — six disclosures
from one field.

It goes into the existing `SecretVault`, which already holds broker credentials.
`BridgeDecl.credentialRef` names it. `AuditParamsFilter` already exists as the choke point for
audit parameters. Export emits a placeholder naming the credential to supply, because an
exported fragment is a file people paste into repositories.

*Consequence.* A test per disclosure path, not one test. The failure mode here is a leak that
nothing fails on.

*Measured.* `BridgeControl` exposes neither `User` nor `Password` (task 1), so the broker
itself never hands the credential back. Every disclosure path that exists is therefore one
Studio would have created, and closing them is entirely within our control.

### 6. Transformer availability is stated as unverifiable, never gated

No management operation reports a broker's loaded classes. The three honest options are: hide
the field (forbidden by non-negotiable #5 — a silently missing button teaches the operator the
product cannot do something), disable it (a claim that has not been checked — ADR-0049 D5:
unknown is not unavailable), or state the uncertainty and let the apply be the evidence.

The third. The field stays enabled, the view says the class cannot be verified until the
apply runs, the apply's hazard names it, and `createVerified`'s read-back names the class when
a node declines to deploy — which turns a mystery into a classpath instruction.

*Alternatives considered.* A curated catalogue of Artemis' built-in transformers with their
documented properties — rejected: a catalogue pinned in Studio drifts from the broker version
it is describing, and a confidently wrong property name is worse than a blank field.

*Consequence.* Transformer properties become editable for diverts too, via the shared
`TransformerFields` component, which closes the existing
`transformerProperties: item?.transformerProperties ?? {}` gap as a side effect rather than as
a separate change.

*What the measurement changed.* Two claims were conflated here and are now separated. Whether
a class is **loadable on a broker** is unknowable before an apply — that part stands, and is
what the hazard and the read-back address. What a **deployed** divert or bridge carries is
fully reported: `TransformerClassName`, `TransformerProperties` and
`TransformerPropertiesAsJSON` all read back with the values supplied (task 1). So a
transformer is compared against each node exactly like any other declared field, and the drift
exclusion originally written into this change's `broker-configuration` delta — and the
matching assumption in `BrokerConfigDocument.sameAs` — were both wrong. `sameAs` is corrected
as part of task 4.1.

### 7. The ELK runner moves to `ui/`, the flow-specific layout does not

`elkInstance()` and `runLayout(ElkNode)` in `features/flow/useFlowLayout.ts` are already
generic — worker when `Worker` exists, bundled build in Node and tests, one instance created
once. They move to `web/src/ui/graph/elk.ts`, which is allowed because `ui/` may import
library code and generated DTO types. `flowLayout.ts`, `useFlowLayout`'s signature-keyed
caching of flow graphs, and everything else in `features/flow` stay put.

This is what ADR-0080 anticipated. The routing graph gets its own `routingLayout.ts` with its
own node sizes, partitions and layout signature, because its columns are not flow's columns.

*Consequence.* One file moves and one import in `features/flow` changes. `features/flow`'s
tests guard that nothing else did.

### 8. Element state is declared-vs-observed, and is never an origin claim

The canvas colours nothing by provenance. It states one of three facts, in words: declared and
observed, declared and not yet applied, observed and not declared. All three are things Studio
knows — the first two from its own document and the drift report, the third from the drift
report's undeclared side.

None of them says where an element came from. ADR-0065 D2 withdrew that vocabulary for a
reason: with no marker on the MBean and no configured-state source, "came from broker.xml" is
not derivable, and Studio will not guess. "Observed and not declared" is a statement about
Studio's declaration, not about the broker's history, and the wording must keep that
distinction visible.

### 9. The graph is bounded, and says so

`operator-ui` requires a view whose size grows with the cluster to be bounded, and ADR-0056
requires degradation by level of detail. Above roughly 150 elements the canvas renders a
region anchored on an address the operator chooses, and states the bound and how much is
outside it. React Flow's `onlyRenderVisibleElements` handles draw cost within the region; the
bound handles comprehension cost, which is the one that actually breaks.

The anchor lives in the URL like every other navigable state, so a bounded view can be shared.

### 10. Keyboard operation is designed, not retrofitted

React Flow nodes are not focusable by default, and a graph is exactly the kind of view where
this gets noticed after release. Each node renders with `tabIndex`, a role and an accessible
name stating what it is and what it connects; arrow keys move between elements; Enter opens
the inspector; Escape returns focus to where it entered.

The Declared tab remains a complete equivalent, which is what makes the graph acceptable at
all rather than merely accessible — the fallback is a real screen an operator already knows,
not a degraded mode.

Nothing on this canvas animates on its own, so `operator-ui`'s pause requirement is satisfied
by construction. That is worth stating rather than leaving implicit, because the neighbouring
Flow screen does animate and the two will be compared.

## Risks / Trade-offs

- **`createBridge` may not exist or may not read back usefully on 2.56** → Measured at task 1
  before any code is written, with a written fallback (Decision 4) that still ships declared
  bridges, export and drift. The measurement goes into ADR-0091 either way, so the next person
  does not have to repeat it.
- **A bridge credential leaks through a path nobody thought of** → Decision 5 enumerates the
  six paths and each gets its own assertion. `AuditParamsFilter` already exists as the audit
  choke point; the risk is the other five.
- **Replacing a bridge has a real gap with no forwarding** → Named as a High hazard before the
  apply can be confirmed, and the canary-first apply means it is observed on one node before it
  happens on the rest. It is not made safe, it is made visible — which is the same contract
  diverts already have.
- **The canvas becomes the fast path and the Declared tab rots** → The two share their
  editors, so behaviour cannot diverge; a drift between them would be a shared-component
  change, not a silent one. The keyboard-only test runs against the canvas, and the existing
  configuration journey test still runs against the tab.
- **Moving the ELK runner touches the Flow screen** → One file, one import, and flow's existing
  layout tests are the guard. Done first, as its own task, so a failure there is isolated from
  the rest.
- **`BrokerConfigDocument` grows another section, and the plan grows another step kind** →
  Both are already shaped for it: the document is a record of lists, the planner already emits
  `SECTION:key:OP` step ids, and ADR-0087's scoped apply works off those ids without knowing
  what sections exist.
- **Bridge configuration is wide — over twenty fields** → Identity fields first, the rest
  behind the disclosure pattern `DivertEditor` already uses, and every field validated against
  the same rules the server enforces so the form and the plan cannot disagree.

## Migration Plan

One Liquibase changeset, additive: `0003-owned-item-bridge.sql` drops and recreates
`ck_broker_config_owned_item_kind` with `BRIDGE` added. No table, no data migration, no
backfill — existing declarations simply have no bridges, and an empty list is the correct
reading of every revision recorded before this change.

Deployment needs no ordering. A cluster whose declaration carries no bridges plans and applies
exactly as it does today; the first bridge appears in a revision an operator saves.

Rollback is disabling the `brokerconfig` feature, which removes the configuration screen, the
builder and the apply together. The changeset is a widened check constraint and is harmless if
nothing writes a `BRIDGE` row; narrowing it again would require first removing any such rows,
which is the ordinary consequence of rolling back a released changeset and is why the
constraint is widened in a new changeset rather than by editing the baseline.
