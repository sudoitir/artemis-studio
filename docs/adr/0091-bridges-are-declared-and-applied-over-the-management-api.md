# ADR-0091: Bridges are declared and applied over the management API

- **Status**: accepted
- **Date**: 2026-09-20
- **Deciders**: Artemis Studio maintainers

## Context

`routing-management` has said, since diverts were first managed, that the system SHALL NOT
offer creating, changing or removing a bridge or a cluster connection. The stated reason:

> Such a change alters how a cluster is wired to other brokers and is invisible to whatever
> manages the cluster's configuration, which would leave the wiring between brokers diverging
> silently from what anyone deploying that cluster believes it to be.

That reasoning was sound when it was written. It was written before the declaration existed.
`feature/brokerconfig` now holds what a cluster is configured to be, reports per node how each
node differs from it, and exports the `broker.xml` that would make a deployment carry it.
An item in the declaration is not invisible to whoever manages the cluster's configuration; it
is a statement of that configuration, with a drift report attached.

The prohibition also left a real hole. A bridge is the only part of a cluster's routing Studio
can show and never change, and *B · Routing builder* names bridges explicitly.

Nothing in this codebase had ever called `createBridge`. ADR-0065 exists precisely because a
different assumption about this management API, held by two features at once, turned out to be
exactly backwards the first time anyone measured it. So it was measured first.

### The measurement

Against `artemis-studio-dev-artemis-primary-1` over Jolokia as the broker's management user,
on **2.44.0** — what `deploy/compose/compose.dev.yaml` pins. The broker was returned to the
state it was found in afterwards.

```
ActiveMQServerControl operations:
  createBridge(java.lang.String)            -> void     (and four positional overloads)
  destroyBridge(java.lang.String)           -> void
  addConnector(String,String) / removeConnector(String)
  (no updateBridge in any overload)

Attributes: BridgeNames, Connectors, ConnectorsAsJSON   -- attributes, not operations
```

**The document uses broker.xml's hyphenated key names**, as `divertConfig` already does:
`name`, `queue-name`, `forwarding-address`, `filter-string`, `static-connectors`,
`discovery-group-name`, `ha`, `use-duplicate-detection`, `retry-interval`,
`retry-interval-multiplier`, `max-retry-interval`, `initial-connect-attempts`,
`reconnect-attempts`, `confirmation-window-size`, `producer-window-size`,
`min-large-message-size`, `check-period`, `connection-ttl`, `routing-type`, `concurrency`,
`client-id`, `user`, `password`.

**A transformer is a nested object**, on bridges and on diverts alike:

```json
"transformer-configuration": { "class-name": "...", "properties": { "a": "1" } }
```

Three other shapes — `transformerConfiguration`/`className`, a flat `transformer-class-name`,
and a nested `transformer` — each **deployed the bridge with the transformer silently
dropped**, returning 200.

**An unknown key is accepted and ignored.** A camelCase document
(`queueName`/`forwardingAddress`/`staticConnectors`) returned 200 and created nothing:

```
createBridge({"name":"probe-bridge2","queueName":...})  -> 200
BridgeNames                                             -> ["probe-bridge"]   (only the hyphenated one)
```

**`BridgeControl` reports thirteen of the twenty-three declarable fields**: `Name`,
`QueueName`, `ForwardingAddress`, `FilterString`, `DiscoveryGroupName`, `StaticConnectors`,
`HA`, `UseDuplicateDetection`, `RetryInterval`, `RetryIntervalMultiplier`, `MaxRetryInterval`,
`ReconnectAttempts`, `TransformerClassName` — plus `TransformerProperties`,
`TransformerPropertiesAsJSON`, and the runtime facts `Started`, `Connected`,
`MessagesAcknowledged`, `MessagesPendingAcknowledgement`, `Metrics`.

Not reported, and therefore not verifiable: `confirmation-window-size`,
`producer-window-size`, `min-large-message-size`, `check-period`, `connection-ttl`,
`routing-type`, `concurrency`, `client-id`, `initial-connect-attempts`, and — see ADR-0092 —
`user` and `password`.

**Concurrency changes the object name.** `concurrency: 2` deploys two MBeans, `<name>-0` and
`<name>-1`; `concurrency: 1` and an unset concurrency both deploy one at the bare `<name>`.
`destroyBridge` takes the **declared** name and removes every instance; called with a name
that is already gone it returns 200 and does nothing.

**Transformer properties are reported in full**, which contradicts the assumption recorded in
`BrokerConfigDocument` (*"transformer excluded: the broker does not report it fully"*):

```
divert probe-divert   TransformerProperties -> {"k": "v"}
bridge t-nested-config TransformerPropertiesAsJSON -> "{\"a\":\"1\"}"
```

**Everything survives a restart.** After `docker restart` and an `Uptime` of 4.459 seconds,
every bridge and divert was still present with its transformer class and properties intact —
ADR-0065's finding for diverts, holding for bridges too.

## Decision

We will **allow a bridge to be declared, applied, changed and removed**, through the
declaration and its existing apply path, and the prohibition in `routing-management` is
superseded.

**D1 — A bridge is a declared item, not a command.** It joins the declaration beside
addresses, queues, address settings, security settings and diverts, and passes through the
same validation, plan, hazards, canary-first apply, verification, drift report and audit.
There is no separate bridge route and no second executor (ADR-0071, ADR-0090 D1).

**D2 — The objection is answered by the mechanism, not waived.** The wiring between brokers
does not diverge silently, because the declaration states what it should be, the drift report
says per node where it does not, and export produces the `<bridge>` element that makes a
deployment carry it. An installation that does not want this withholds the permission the
declaration's apply already requires.

**D3 — A change is a removal and a creation.** There is no `updateBridge` in any overload, so
a changed bridge is planned and presented as a destroy then a create, and named as a High
hazard: nothing is forwarded between the two steps and the source queue accumulates. The same
discipline `routing-management` already applies to changing a divert.

**D4 — The read-back is mandatory, not defensive.** The broker answers 200 for a document it
silently ignores. `createVerified` therefore reads the bridge back after writing it and
reports `APPLIED`, `ALREADY` or `FAILED` naming the differing fields — the same shape
`DivertOperations.createVerified` already has, and for the same measured reason.

**D5 — Verification covers what is reported, and claims nothing else.** Ten declarable fields
are write-only from the management API's point of view. They are still declarable, because
they are real configuration and the export carries them correctly, but an applied bridge is
reported as matching only on the fields the broker hands back. Absence of evidence is not
agreement — the discipline ADR-0049 D5 applies to the write capability and ADR-0065 D2 to a
divert's origin.

**D6 — Concurrency is part of identity.** A bridge declared with a concurrency above one is
recognised by its `<name>-0 … <name>-(N-1)` instances, and removed by its declared name.
Without this the read-back would report a correctly deployed concurrent bridge as missing.

**D7 — A transformer is compared like any other field.** The measurement shows the broker
reports the class and the properties in full, for bridges and diverts alike. The exclusion in
`BrokerConfigDocument.sameAs` is corrected, and a transformer difference is ordinary drift,
classified per node and reported by naming the field. Whether a class is *loadable* remains
unknowable before an apply; that is a hazard and a per-node failure, not a drift exclusion.

**D8 — Adoption does not adopt bridges.** Offering a cluster's current state as revision 1
reads what the broker reports, and for a bridge that is thirteen fields of twenty-three. An
adopted bridge would therefore declare the other ten as unset, and the next apply would write
the broker's defaults over them — silently changing window sizes, connection TTL, check
period, concurrency and the client id on a bridge nobody edited. Adoption stays diverts,
queues, addresses and settings; an existing bridge is reported by the drift report as observed
and not declared, which is true and visible, and an operator declares it deliberately. This is
the same reasoning as D5: what cannot be read back is not claimed.

## Consequences

Good: the last unmanageable part of a cluster's routing becomes manageable, through the path
that already carries hazards, the canary, verification, drift and audit.

Good: the measurement is recorded rather than assumed, and it corrects a second thing on the
way past — the transformer exclusion, which had been carried as a comment nobody had tested.

Bad: Studio can now stop traffic between brokers. Removing a bridge is silent on this cluster
— nothing here reports that the far side stopped receiving — which is why it is a High hazard
rather than an ordinary removal.

Bad: an existing bridge is not adopted (D8), so a cluster that already runs bridges shows
them as undeclared until someone declares them. That is a real chore, and the alternative was
silently rewriting ten fields on first apply.

Bad: thirteen fields of twenty-three verify. An apply that silently dropped a window size
looks clean, and only the export would show it. D5 makes the system say so rather than imply
otherwise, but it cannot make the broker report more.

Bad: this is version-specific, measured on 2.44.0. `CLAUDE.md` and ADR-0065 both state the
project pins 2.56.0, and ADR-0065's own measurement names a container
(`artemis-studio-dev-artemis-secondary-1`) that the current compose file does not define. That
discrepancy is recorded here and is not resolved by this change; it should be settled before
the next broker-facing measurement is trusted.

## Alternatives considered

- **Keeping the prohibition.** Honest and cheap, and it was right for as long as there was no
  declaration. Rejected: the objection names a consequence the declaration now prevents, and
  keeping the rule would leave a third of the roadmap item undeliverable for a reason that has
  stopped being true.
- **A live bridge API mirroring the divert routes.** Rejected: a second write path (ADR-0071),
  and it would recreate exactly the silent cross-broker divergence the prohibition guarded
  against, because nothing would record what the cluster is supposed to be.
- **Declaring bridges but refusing to apply them — export only.** The fallback if the
  measurement had failed. Rejected on the evidence: the operations exist, the read-back is rich
  enough to verify the fields that matter, and refusing to apply would leave the operator
  pasting XML for a change the broker accepts.
- **Positional `createBridge` overloads.** Rejected: seventeen and nineteen-argument
  signatures, no way to omit a field, and `JolokiaRequest.exec` cannot carry a null argument —
  the same reason diverts use the single-document overload.
- **Treating an unreported field as matching.** Rejected under D5: it is the comfortable lie,
  and it would make a silently dropped field look verified.
