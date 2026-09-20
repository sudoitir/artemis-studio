## Why

Roadmap item **B · Routing builder** reads "visual builder for diverts, bridges, and
transformers". Three quarters of the substrate is already shipped — diverts have full
create/delete with preflight and per-node outcomes, the declaration engine already carries
diverts through plan, hazards, canary apply and drift, and `@xyflow/react` with `elkjs` and
an ELK worker are already in the tree for the Flow screen. ADR-0080 even names the routing
builder as the graph that would reuse that worker.

What is missing is the part the roadmap line actually promises, plus two real gaps:

- **There is no picture.** Routing is two paged tables. "Where does traffic on `ORDER.IN`
  actually end up" is reconstructed in the operator's head from a divert row, an address, a
  queue row and a bridge row, on four screens, during an incident.
- **Bridges are unmanageable.** `routing-management` forbids mutating them outright, and the
  declaration document has no bridges at all. A cluster's wiring to other brokers is the one
  part of its routing Studio can show and never change.
- **Transformers are carried but never authored.** `DivertDecl` has `transformerClassName`
  *and* `transformerProperties`, and `BrokerXmlCodec` round-trips both — but the editor
  exposes only the class name and passes the properties through untouched. A transformer that
  takes configuration cannot be expressed in Studio at all.

## What Changes

- **A routing canvas, as a mode of the one configuration screen.** Addresses, queues, diverts,
  bridges and remote targets in one ELK-laid graph, with direct manipulation: dragging between
  two addresses proposes a divert, dragging from a queue to a remote target proposes a bridge,
  and selecting anything opens the same editor the Declared tab opens.
- **The canvas authors the declaration, never the broker.** Every edit is local until it is
  saved as a revision; applying goes through the existing plan → hazards → canary → drift
  path. There is no second write path, and authoring is deliberately not previewing: the
  preview is still the apply drawer, whose inputs are frozen and whose plan hash still guards
  a cluster that moved.
- **BREAKING: bridges become declarable and mutable**, superseding *"Bridges are never mutated
  through the system"*. A bridge is declared like a divert and applied over the management API
  by the same engine, so the objection that motivated the prohibition — wiring between brokers
  diverging silently from what anyone deploying the cluster believes — is answered by the
  declaration being that configuration, by per-node drift, and by `broker.xml` export.
- **A changed bridge is a remove and a create**, because Artemis has no update, and it is
  named as a High hazard: forwarding stops between the two steps and the source queue
  accumulates. The same discipline diverts already have.
- **Bridge credentials are vaulted, never declared.** The declaration carries a reference; the
  secret lives in the existing vault and reaches no document, revision, diff, audit parameter,
  MCP tool or exported fragment.
- **Transformers become authorable** on both diverts and bridges — class name plus its
  properties — with the honest statement that Studio cannot verify the class is on a broker's
  classpath, and the per-node read-back naming the class when a node declines to deploy.
- **Element state is stated in words**: declared and running, declared but not yet applied,
  running but not declared. No origin is claimed for anything (ADR-0065 D2) — "declared here"
  and "observed there" are both facts Studio holds, and neither is a guess about where a
  divert came from.

## Capabilities

### New Capabilities

None. This change extends three existing capabilities rather than introducing a concept the
product does not already name.

### Modified Capabilities

- `routing-management`: the prohibition on mutating bridges is replaced by declaring and
  applying them like other configuration; the routing view gains a graph that answers "where
  does traffic on this address go" directly; transformers gain properties and an honest
  reachability statement.
- `broker-configuration`: the declaration gains bridges, their apply ordering and hazards, and
  credential handling by reference; diverts and bridges gain transformer properties.
- `operator-ui`: a graph editor is keyboard-operable and always has a non-graph equivalent; an
  authored-but-unapplied edit is stated in words.

## Impact

- **Backend**: `feature/brokerconfig` gains `BridgeDecl` and `TransformerDecl` in
  `BrokerConfigDocument`, bridge parse/write in `BrokerXmlCodec`, bridge steps in
  `BrokerConfigPlanner` (ordered after diverts), bridge apply in `BrokerConfigApplyService`,
  bridge comparison in `BrokerConfigDriftService`, bridge validation in
  `BrokerConfigValidator`, and four new `HazardClass` values. `feature/queues` gains
  `BridgeOperations` beside `DivertOperations`, taking `listBridges` with it.
  `platform/broker` gains a connector-name read and a `<bridge>` snippet generator.
- **Database**: one changeset. `ck_broker_config_owned_item_kind` currently allows only
  `ADDRESS_SETTING`, `SECURITY_SETTING` and `DIVERT`; it gains `BRIDGE`. No new table — the
  declaration is a jsonb document.
- **API**: the declaration, plan, apply, diff and drift routes carry bridges in their existing
  shapes. Three routes are added, both of which the capability needs and neither of which
  carries a bridge's configuration: `GET /config/connectors`, so the bridge editor can offer a
  node's connector names, and `GET`/`PUT`/`DELETE /config/bridge-credentials[/{ref}]`, which is
  how a credential reaches the vault without passing through the declaration (ADR-0092 D1).
  The read is scoped to `cluster:read` like every sibling read; the writes to config write.
- **Frontend**: `web/src/features/brokerconfig/routing/` — the canvas, its pure graph and
  layout modules, the inspector, a bridge editor and shared transformer fields.
  `DivertEditor.tsx` is amended to use them. The generic ELK runner moves from
  `features/flow/useFlowLayout.ts` to `web/src/ui/graph/elk.ts`, as ADR-0080 anticipated;
  `flowLayout.ts` stays where it is. No new dependency — `@xyflow/react` and `elkjs` are
  already here. No new feature module, no new eslint boundary edge, no new nav group.
- **Broker load**: one additional batched read per serving node on the configuration screen,
  for the `ConnectorsAsJSON` attribute. Nothing is added to the scrape.
- **Broker version**: the behaviour this change relies on was measured on **2.44.0**, which is
  what `deploy/compose/compose.dev.yaml` pins. `CLAUDE.md` and ADR-0065 both say the project
  pins 2.56.0, and ADR-0065's measurement names a container this compose file does not define.
  That discrepancy is recorded in ADR-0091 and is not resolved by this change.
- **ADRs**: 0090 (the builder is a canvas over the declaration), 0091 (bridges are declarable
  and applied over the management API — superseding the prohibition, carrying its measurement),
  0092 (bridge credentials are vaulted, never declared). Depends on 0049, 0056, 0065, 0067,
  0071, 0074, 0078, 0080, 0082, 0087.
- **Not in scope**: cluster connections, which are a different construct with cluster-wide
  failure modes and no per-node read-back; acceptors, which the management API cannot create
  at all and which belong to roadmap item *C · Static configuration verification*; connectors,
  which the management API *can* add and remove but which are a broker-wide transport concern
  rather than a routing one, and which this change only reads in order to offer them to the
  bridge editor; a transformer catalogue, which would drift from the broker version; the live
  divert create/delete API in `feature/routing`, which is untouched.
