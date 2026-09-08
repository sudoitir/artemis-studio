# ADR-0065: Runtime broker configuration persists across restarts

- **Status**: accepted
- **Date**: 2026-09-07
- **Deciders**: Artemis Studio maintainers

## Context

Two features in flight rest on the same assumed fact: that configuration created over the
Artemis management API is runtime-only and disappears when the broker restarts.

Change 04 (`divert-and-bridge-management`, since absorbed into
`06-message-capture-and-routing`) treated it as the hazard to disclose — an operator who
creates a divert through Studio must be told it will be gone after the next restart, and
shown the `broker.xml` that would make it permanent. ADR-0062 treated the same fact as the
third of three cleanup guarantees for a capture tap: Studio running reclaims an orphan,
Studio dead leaves a capped ring, and a broker restart removes the tap entirely.

Neither had been measured. It is measurable in a minute against the dev stack, and the
result is the opposite.

### The measurement

Against `artemis-studio-dev-artemis-secondary-1` (Artemis 2.56.0, the version this project
pins), over Jolokia, as the broker's own management user:

```
createDivert("probe.divert", "probe.routing", "PROBE.IN", "PROBE.OUT", false, null, null)  -> 200
addAddressSettings("probe.settings.#", {"addressFullMessagePolicy":"DROP", ...})            -> 200
addSecuritySettings("probe.settings.#", ...)                                               -> 200

docker restart artemis-studio-dev-artemis-secondary-1
                                                        Uptime                -> 9.026 seconds
                                                        DivertNames           -> ["probe.divert"]
getAddressSettingsAsJSON("probe.settings.x")            addressFullMessagePolicy -> DROP
getRolesAsJSON("probe.settings.x")                                            -> present
```

All three survived. `probe.settings.#` appears in no `broker.xml` in this repository, so the
address setting and the security setting were resolved from broker state written at runtime,
not from a file. `destroyDivert`, `removeAddressSettings` and `removeSecuritySettings`
removed all three afterwards, and the broker was left as it was found.

A second measurement, taken at the same time, settles a related question. `DivertControl`
exposes exactly `Address`, `ForwardingAddress`, `Filter`, `Exclusive`, `RoutingName`,
`UniqueName`, `RoutingType`, `TransformerClassName`, `TransformerProperties`,
`TransformerPropertiesAsJSON` and `RetroactiveResource`. Nothing on it records where the
divert came from. `ActiveMQServerControl` has no operation that returns configured but
undeployed diverts — the closest are `reloadConfigurationFile` and `exportConfigAsProperties`,
neither of which reads configuration back.

## Decision

We will treat **management-created broker configuration as durable**, and we will not tell an
operator that anything Studio creates over the management API will disappear on its own.

**D1 — The hazard is drift, not evaporation.** A divert Studio creates outlives the broker
process and is invisible to whatever manages that broker's `broker.xml`. The next
configuration-managed deployment of that broker will not carry it, and the two will disagree
without either side reporting a problem. That is the sentence the routing view owes the
operator, and it is the opposite of the sentence change 04 planned to show. The remedy is
unchanged: the generated `broker.xml` that would make the running state and the configured
state agree.

**D2 — Studio does not claim a divert's origin.** With no marker on the MBean and no
configured-state source, Studio cannot tell a `broker.xml` divert from one another tool
created at runtime, and it will not guess. The routing view states what it knows — the divert
exists, here is what it does, here is the configuration that would match it — and attributes
only the diverts Studio itself owns, from Studio's own records. The three-way
configured-and-running / running-but-not-configured / configured-but-not-running vocabulary
is withdrawn, because two of its three values are not derivable and the third would be
asserted without evidence.

**D3 — A capture tap is bounded by size and by age, not by a restart.** ADR-0062's third
cleanup guarantee is void. In its place the capture address carries `expiry-delay` alongside
`ring-size` and `address-full-policy=DROP`, with `auto-create-expiry-resources=false` so
expired copies are dropped rather than moved to an expiry address that then holds them. An
abandoned tap therefore holds at most `ring-size` messages, none older than the expiry delay,
for as long as the broker runs. Bounded in both dimensions, with nothing alive to enforce it.

**D4 — Removal is always explicit.** Because nothing removes a tap for us, the reconciler's
orphan sweep is not an optimisation, and deleting a capture subscription must state that it is
what removes the broker objects. See ADR-0062 D6.

## Consequences

Good: the product stops making a false promise. An operator who is told a divert is temporary
and finds it three months later has been misled by us; an operator who is told it will drift
from `broker.xml` has been told something they can act on.

Good: the measurement is cheap and repeatable, and is now recorded rather than assumed.

Bad: capture's cleanup story is genuinely weaker. Two guarantees where the design assumed
three, and the remaining pair both require the tap to have been installed with the right
address settings — which is why installing them is a precondition of capture and not a
best-effort extra (ADR-0062 D8).

Bad: this is a version-specific fact. It was measured on 2.56.0 and is recorded as such. A
broker old enough to predate persisted management configuration would behave as change 04
assumed; Studio will not detect that, and will simply be conservative on such a broker.

## Alternatives considered

- **Boot observation to recover the classification.** Record each node's divert set at the
  first poll after its uptime resets; anything appearing later was created at runtime. Real
  evidence, and it would work for diverts other tools created. Rejected for now: it needs new
  state and time to become useful, it still cannot produce configured-but-not-running, and the
  drift statement in D1 is actionable without it.
- **Keeping the vocabulary and reporting `UNKNOWN`.** Honest, but nearly every divert would
  read `UNKNOWN`, which is a column that costs width and answers nothing.
- **Auto-deleting the capture queue when its consumer disappears.** Would bound an orphan
  without an expiry delay. Rejected: the divert would then route to a missing address, which
  auto-creation can recreate, so the tap can resurrect itself.
