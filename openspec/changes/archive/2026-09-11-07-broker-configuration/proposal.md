## Why

Studio can read a node's address settings and security settings, and it can create a
divert, an address or a queue — one at a time, on every live node, with a per-node
outcome. For everything else it hands the operator a `broker.xml` snippet and says
"add this". That is honest, and it leaves the cluster without a declared shape: nothing
in the product says what a cluster's address settings, security settings and diverts are
*supposed* to be, so the only comparison available is node against node
(`broker-config-diff`), which cannot see a cluster that is uniformly wrong.

Meanwhile the thing Studio warns about is real and measured. Configuration created over
the management API persists across restarts ([ADR-0065](../../../docs/adr/0065-runtime-broker-configuration-persists-across-restarts.md)),
so every divert created in the routing view is a silent disagreement between the running
broker and whatever deploys its `broker.xml`. The remedy Studio offers today is a snippet
to copy. The remedy an operator actually needs is to choose, per cluster, which side owns
the configuration — and to have Studio keep the nodes honest against that choice.

The operator's constraint, stated during design and binding on every decision below:
**this must not break the broker.** A configuration apply that takes traffic away from an
address on three nodes at once is an outage, not a feature.

Absorbs change `05-desired-state-drift` (unapplied). Its declaration of queues and
addresses, its advisory-only drift and its opt-in reporting of undeclared resources are
carried into the `broker-configuration` capability, widened to address settings, security
settings and diverts. The advisory-only ADR that change referenced as "0053" was never
written; [ADR-0067](../../../docs/adr/0067-broker-configuration-declared-applied-canary-first.md)
records it.

## What Changes

**A new capability, `broker-configuration`.** A cluster has one **declaration**: its
addresses (with their queues), address settings, security settings and diverts, as a
typed document Studio stores and versions. An operator edits it as a form, imports it from
a pasted `broker.xml` (recognised / changed / **unsupported** listed, nothing dropped),
exports it as a `<core>` fragment, or adopts it from what the cluster is running.

**Two apply modes, chosen per cluster.** `STUDIO_MANAGED`: Studio applies the declaration
over the management API, durably (ADR-0065). `CONFIG_MANAGED`: Studio generates the
fragment and never applies; the apply control stays visible, disabled with the reason.
Drift is evaluated in both modes.

**Apply is a plan.** A dry run reads every live node once and lists, per node, only the
steps whose observed state differs from the declaration, in dependency order, with before
→ after for every key the broker reports. The plan names **hazards** — a policy that
drops messages, a limit already below current usage, a security match covering the
management address, a `#` match, an exclusive divert, a key that will change although
the declaration does not set it — and a real run must acknowledge every High hazard and
be confirmed by typing the cluster name.

**Canary, verify, halt.** One node receives the plan and is re-read to verify it before
the next node begins; the first failure stops the run and reports the rest as not
attempted. Nothing is rolled back and nothing beyond the failed node is touched. Re-running
converges.

**Studio removes only what it applied and never destroys a queue or address.** Items
Studio applies are recorded as Studio-owned. An undeclared item is reported; removing it
is an explicit opt-in with its own hazard.

**Drift is advisory, always.** Evaluation runs on a schedule (a bounded batched read per
live node, under the cluster lock) and on demand; action never does. Drift is an alertable
cluster-state condition.

**Two new permissions.** `config:write` edits a declaration; `config:apply` applies one
to brokers. Apply is a create-and-update authority: it never destroys a queue or address.

**MCP.** One read tool (`broker_config`) and one mutating tool (`broker_config_change`)
that dry-runs by default, returns the plan and the acknowledgement ids it needs, and acts
only with a confirmation.

**Static `<core>` settings are out of scope** (`global-max-size`,
`persist-delivery-count-before-delivery`, `<ha-policy>`): no version of the management
API can apply them. Import lists them as unsupported; nothing is stored. Roadmap
follow-up: static configuration verification.

## Capabilities

### New Capabilities

- `broker-configuration` — declaration, import/export, adopt, plan with hazards,
  canary-verify-halt apply, ownership, drift, history.

### Modified Capabilities

- `authorization` — `config:write` and `config:apply`, and what apply does not imply.
- `mcp-server` — the two tools; the stale "lost when the broker restarts" sentence in the
  routing requirement is corrected.
- `alerting` — configuration drift as a cluster-state condition.
- `broker-config-diff` — links to the declaration and states how the two differ.
- `routing-management` — a divert may also be declared; nothing else changes.

## Impact

- **Persistence:** one new Liquibase changeset (`024-broker-configuration.sql`) with five
  tables: declaration, revision, apply, per-node state, owned item. Column order per
  non-negotiable #7; storage parameters on the per-node state table. The declaration is
  not a broker-derived cache and is never cleared with one.
- **Settings registry:** `config.drift-interval`, `config.apply-step-cap`.
- **Audit:** `EDIT_BROKER_CONFIG` (no broker call) and `APPLY_BROKER_CONFIG` (one event
  per apply, dry runs included, node × step detail in `outcome_detail`).
- **Alerting:** `CONFIG_DRIFT` state condition and a rule template.
- **SSE:** a `config` topic.
- **Frontend:** a new cluster section, *Configuration*, with Declared / Drift / History
  tabs and a dedicated apply route.
- **Docs:** ADR-0067 and its index row; `site/src/guide/broker-configuration.md` in the
  three locales' sidebars; the MCP guide's tool table (stale, corrected while touched);
  README roadmap rows in three languages.
- **Removed:** `openspec/changes/05-desired-state-drift/`.

### Follow-ups, not in this change

Static configuration verification; `${PLACEHOLDER}` values and per-environment variables
(import rejects them, naming the field); environment promotion; recording a divert
created in the routing view into the declaration; moving the config-diff screen under
the new section (cross-linked instead); XML escaping in `BrokerXmlSnippets.forDivert`
(the new writer escapes; the old helper is untouched).
