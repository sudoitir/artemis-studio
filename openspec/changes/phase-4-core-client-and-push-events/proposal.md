## Why

Phases 0–3 shipped a working product on one transport: everything Studio knows
it learned by *asking* over Jolokia, and its SSE stream carries only "something
changed, refetch" signals. Two limits are now blocking: Jolokia is
request/response only, so consumer/session/connection/binding activity — and the
temp-reply-queue lifecycle the Phase 5 flagship depends on — is invisible; and
the Jolokia management channel stringifies and truncates message bodies, so
binary payloads cannot be read or written faithfully. Phase 0 already captured
the real `activemq.notifications` catalogue and both broker.xml requirements, and
`artemis-jakarta-client`, `broker_node.core_url` and `broker_credential.kind =
'CORE'` are all present and unused, pinned for this phase. This change turns the
Core protocol client on.

## What Changes

- **New Core protocol client.** A per-live-node subscriber on
  `activemq.notifications`, reconciled off the tier-A scrape cycle so it follows
  failover, polling with `receive(timeout)` (a `MessageListener` deadlocks on
  the pinned client/broker pairing), with Studio-driven reconnect/backoff
  outside the per-node call limiter.
- **Notifications become real domain events.** Each notification is normalised to
  a `BrokerEvent` (type, node, address, consumer/session/connection identity,
  timestamp, and the raw `_AMQ_*` map) and persisted to a new `broker_event`
  history table — buffered batch insert, a bounded queue whose overflow
  increments a *visible* drop counter, and an hourly retention reaper.
- **New events history API and screen** —
  `GET /api/v1/clusters/{id}/events` (paged; filter by type / node / address /
  time) and a routed Events view modelled on the audit-log screen. The screen
  renders an explicit unavailable state, with the broker.xml snippet, when
  notifications are not subscribed.
- **SSE gains a data-bearing `events` topic.** Signal topics are unchanged; the
  `events` topic carries the `BrokerEvent` payload and an event id
  (`broker_event.seq`), and a reconnecting client replays missed events via
  `Last-Event-ID` (bounded). Notification-derived staleness of the
  `consumers` / `sessions` / `connections` / `queues` views is fanned out as
  those existing signal topics, **coalesced to at most one per topic per second
  per cluster** because each such refetch costs one Jolokia call per node.
- **`NOTIFICATIONS` capability stops returning `UNKNOWN`.** The probe reads the
  Core subscription's cached outcome: `AVAILABLE` when subscribed (still shipping
  the `NotificationActiveMQServerPlugin` snippet, since an idle broker and a
  plugin-less one are indistinguishable); `UNAVAILABLE` with the exact
  security-setting or acceptor snippet when the subscription was refused or no
  Core URL is reachable. The frontend stops excluding `notifications` from the
  connection-setup prompt.
- **Faithful message I/O over Core.** A `MessageTransport` interface is extracted
  from two real implementations (ADR-0002 said it would be, in this phase). The
  Jolokia implementation is the existing browse/operations code, unchanged, and
  stays the fallback. The Core implementation reads via a `QueueBrowser` and
  writes real typed properties and `BytesMessage` bodies; it **delegates every
  by-id / by-filter mutation to the Jolokia implementation** (those are
  payload-free management operations with no fidelity dimension), and falls back
  to Jolokia past a bounded browse depth. `BrowsedMessage.body` widens from
  `String` to `byte[]` + an encoding; responses state which channel served them.
- **Registration and node overrides gain a Core dimension.** An optional CORE
  credential (defaulting to the Jolokia credential when omitted) and an optional
  manual Core URL per node (discovery stores the broker-advertised connector,
  which is usually unreachable from where Studio runs).
- **New settings** — `events.retention-hours` (default 72) and
  `events.buffer-size`, runtime-overridable like the existing operational
  settings.
- **Four ADRs (0026–0029).** ADR-0021 (message operations Jolokia-only) is
  **superseded**, not edited. ADR-0018 (SSE events are signals) is **extended**
  for the one `events` topic, not edited.
- **New test infrastructure** — a process-wide singleton Artemis Testcontainer
  (no new dependency); `NotificationSpikeIT` is promoted from a disabled
  hand-run spike into a real integration test.

## Capabilities

### New Capabilities

- `broker-events`: subscribing to `activemq.notifications` over the Core
  protocol client per live node, normalising each notification to a typed
  domain event, persisting a bounded and reaped history, and serving that
  history through a filtered paged API and a routed screen.
- `core-transport`: the Core protocol connection model — one subscription per
  serving node, reconciled with the topology and following failover; a dialable
  Core URL resolved from discovery or a manual override; a CORE credential
  defaulting to the Jolokia credential; Studio-driven reconnect with backoff,
  outside the per-node call limiter.

### Modified Capabilities

- `broker-capabilities`: `NOTIFICATIONS` is no longer fixed at `UNKNOWN` — it is
  a determined `AVAILABLE` / `UNAVAILABLE` verdict from the Core subscription
  outcome, cached rather than probed live, each state carrying its specific
  `broker.xml` snippet.
- `realtime-stream`: the stream gains a data-bearing `events` topic (payload +
  event id, not a bare signal) with bounded `Last-Event-ID` replay; events may
  now originate from the push path, not only the scrape path; notification-driven
  signal emissions for the resource views are coalesced per cluster.
- `message-operations`: message browse, single-message read and send are served
  over the Core client with faithful typed properties and binary bodies when a
  Core connection is available, falling back to Jolokia otherwise; every message
  response declares which channel served it; the truncation disclosure applies
  only to the Jolokia channel.
- `cluster-registration`: registration accepts an optional CORE credential
  (defaulting to the Jolokia credential); cluster removal also releases the
  cluster's Core connections and subscription state.
- `cluster-topology`: a node's manual address override applies independently to
  its Jolokia URL and its Core URL, and a node-override request must set at
  least one of the two.
- `studio-settings`: the persisted operational settings set gains the event
  history retention window and the event write-buffer size.

## Impact

- **Code (backend, new)**: `broker/core/*` (connection factory, event client,
  subscription manager, notification mapper, URL resolver, `BrokerEvent`),
  `broker/{MessageTransport,JolokiaMessageTransport,CoreMessageTransport}`,
  `persist/BrokerEvent*` (entity, repository, buffered writer, reaper),
  `service/BrokerEventService`, `sse/EventStreamPublisher` (+ coalescer),
  `web/EventController`, `web/dto/EventViews`, `mapper/EventViewMapper`.
- **Code (backend, modified)**: `broker/CapabilityProbe`,
  `broker/BrokerConnections`, `broker/BrokerXmlSnippets`,
  `broker/MessageBrowser` (`body` → `byte[]`), `persist/BrokerNodeEntity`,
  `sse/SseHub`, `web/StreamController`, `web/dto/{ClusterRequests,MessageViews}`,
  `service/{MessageService,ClusterService,SettingsService}`,
  `config/ArtemisStudioProperties`, `scheduler/ScrapeScheduler`.
- **Code (frontend)**: new `web/src/events/*`; modified `web/src/api/stream.ts`,
  `web/src/api/client.ts`, `web/src/router.tsx`, `web/src/app/ClusterLayout.tsx`,
  `web/src/messages/MessageDetailPanel.tsx`, cluster registration/node forms,
  `web/src/test/setup.ts` (`EventSource` stub); regenerated
  `web/src/api/schema.d.ts` and `web/openapi.json`.
- **APIs**: adds `GET /api/v1/clusters/{id}/events`; adds the `events`,
  `consumers`, `sessions`, `connections` SSE topics and `Last-Event-ID` handling
  on `GET /api/v1/stream`; message responses gain `transport` and `bodyEncoding`;
  registration and node-override request bodies gain optional Core fields.
- **Schema**: one new changeset, `010-broker-events.sql` (`broker_event` table +
  storage parameters). Changesets 001–009 untouched.
- **Dependencies**: none added. `artemis-jakarta-client:2.56.0` and
  `testcontainers-junit-jupiter` are already on the pom.
- **Config**: `application.yml` gains an `artemis-studio.events` block
  (retention, buffer size, flush interval, coalesce window); the first two are
  overridable from `studio_setting`.
- **Docs**: ADRs 0026–0029 added, ADR-0021 marked superseded, ADR-0018 annotated;
  `docs/architecture.md` "Event path (push)" section made real;
  `docs/broker-management-notes.md` gains a Phase 4 section; README Phase 4 rows
  ticked; `openspec/project.md` current-phase line updated.
