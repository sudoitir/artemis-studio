## Why

Two things make an Artemis estate fail at 3 a.m. without anyone being told in time.

**Alerts reach too few places.** A firing rule can be sent to a Slack incoming webhook or
a signed generic webhook, and nowhere else. Most on-call rotations run through
PagerDuty (or a tool that speaks its Events API), many organisations use Microsoft
Teams instead of Slack, and email is still the channel of last resort. An operator who
cannot route a split-brain alert to the pager has to poll the Alerts screen. The
channel screen is also a single inline form. A channel cannot be edited or disabled.
Nothing shows whether its last delivery failed, and a failed test is only a toast.
This is roadmap item A, *Alert delivery*.

**The commonest cluster mistakes are silent until failover.** The dev compose stack
shows the classic one: a single replication primary/backup pair on quorum voting. With
one primary, Artemis skips the vote (`AMQ221083: ignoring quorum vote as max cluster
size is 1`), so on a network partition the backup always promotes itself: a
split-brain waiting to happen. Other quiet mistakes look the same way. Load balancing is
`ON_DEMAND` while `redistribution-delay` keeps its default of `-1`, so messages are
stranded on a node with no consumers. A connector advertises `localhost` to the cluster.
A cluster connection does not see every member. Persistence is off, disk usage is
unbounded, or there is no dead-letter address. Studio already reads the configuration
that reveals every one of these (the broker MBean, address settings, acceptors,
connectors and cluster connections), but nothing reviews it.

## What Changes

- **Three new notification channel kinds**:
  - **Email** over SMTP (STARTTLS, implicit TLS or plain; optional authentication; one
    or more recipients), sent as HTML with a plain-text alternative.
  - **Microsoft Teams** through a Workflows (or legacy incoming) webhook, sent as an
    Adaptive Card.
  - **PagerDuty Events API v2**: one `trigger` event per firing subject and one
    `resolve` event per resolution, deduplicated on rule and subject, so a PagerDuty
    incident opens and closes with the alert. The endpoint can be changed, so the EU
    service region and any PagerDuty-compatible receiver work too.
- **Every notification can be read as it is.** It carries the cluster name, a
  readable subject (a node's name rather than its id), the observed value, the time,
  and a link back to Studio when a public URL is configured. The generic webhook
  payload gains the same fields; existing fields are unchanged.
- **Channel management**:
  - Channels are created and edited in a per-kind editor, with visible labels and
    validation on blur.
  - A channel can be enabled and disabled.
  - An edited, unsaved configuration can be tested. The test reports its outcome
    and cause inline.
  - Each channel shows the health of its last delivery and a delivery log (state,
    attempts and last error). A dead delivery can be retried.
  - Deletion states how many rules the channel is bound to, and needs typed
    confirmation.
- **Setup review**, a new capability:
  - Studio reviews each cluster's HA, clustering, durability and message-safety
    configuration against a catalogue of known mistakes. Each finding states what is
    wrong, what it costs, the evidence per node, and the `broker.xml` that fixes it.
  - Findings are graded critical, warning or info.
  - The review is **one batched Jolokia read per node**. It runs on a slow interval
    (default 15 minutes) and on demand.
  - A node that did not answer is stated. The review never treats it as healthy.
  - What the management API cannot show (for example `network-check-list`) is said
    in words. It is never assumed.
  - A finding can be **accepted as a known risk**, with a reason and an optional
    expiry. The acceptance is audited, and the finding stays visible as accepted.
- **Setup risk can alert.** A new `SETUP_RISK` state condition fires for each open,
  unaccepted critical or warning finding. It is offered as a rule template, not seeded.

## Capabilities

### New Capabilities
- `cluster-setup-review`: reviewing a cluster's HA, clustering and durability
  configuration for known mistakes, stating the evidence and the fix, and accepting a
  finding as a known risk.

### Modified Capabilities
- `alerting`: email, Microsoft Teams and PagerDuty channels; readable notification
  content; channel editing, testing, health and delivery log; retrying a dead
  delivery; the `SETUP_RISK` state condition.

## Impact

- **Backend**
  - `feature/alerting` gains three `NotificationSender`s, a shared message formatter,
    and per-kind channel validation.
  - It also gains `GET /api/v1/channels/{id}/deliveries`, `POST
    /api/v1/channels/{id}/deliveries/{seq}/retry`, `POST /api/v1/channels/test`, and a
    test endpoint that returns its outcome instead of failing the request.
  - New setting `alerting.email-timeout`, and the property `artemis-studio.alerting.public-url`.
  - Changeset `feature/alerting/0002` widens the channel-kind check, and the state-condition check. The state-condition check never accepted `CONFIG_DRIFT`, so a configuration-drift rule was refused by the database; this change fixes that alongside `SETUP_RISK`.
  - New module `feature/setupreview`:
    - a reader (one batched POST per node);
    - a pure rule catalogue;
    - tables `setup_review`, `setup_finding` and `setup_finding_acceptance`;
    - a scheduled job under a new `ClusterLock` scope;
    - REST under `/api/v1/clusters/{clusterId}/setup-review`, SSE topic
      `setup-review`, an MCP tool, and an `AlertSignalSource` for `SETUP_RISK`.
- **Frontend**
  - The notification-channel settings section is rebuilt around a channel editor, a
    delivery log and typed delete.
  - New feature `setupreview` with a *Setup review* view under Configuration, and a
    `SETUP_RISK` rule template in the rule form.
- **Broker footprint**: one extra batched read per node every 15 minutes. It makes no
  writes.
- **ADRs**
  - ADR-0105: email, Teams and PagerDuty channels.
  - ADR-0106: setup review is a rule catalogue over one batched read.
- **Dependencies**: `spring-boot-starter-mail` (Jakarta Mail), per ADR-0105.
