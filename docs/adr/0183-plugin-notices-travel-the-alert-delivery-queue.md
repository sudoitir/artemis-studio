# ADR-0183: Plugin notices travel the alert delivery queue

- **Status**: accepted; amends [ADR-0036](0036-notification-delivery-queue-and-channel-signing.md)
- **Date**: 2026-10-07
- **Deciders**: Mahdi Amirabdollahi

## Context

An approval provider, or any plugin, may need to tell people outside Studio that something waits
for them: in Slack, Teams, email, PagerDuty or a signed webhook. Those channels exist in alerting
([ADR-0036](0036-notification-delivery-queue-and-channel-signing.md),
[ADR-0105](0105-email-teams-and-pagerduty-notification-channels.md)), with a durable `alert_delivery`
outbox claimed with `FOR UPDATE SKIP LOCKED`, backoff and a DEAD state. The outbox is tied to alert
rules: `alert_delivery.rule_id` is `NOT NULL`. Channel configuration holds secrets.

## Decision

1. **The outbox carries two kinds.** `alert_delivery` gains `kind` (`alert` or `notice`) and
   `source`; `rule_id` becomes nullable with `CHECK ((kind='alert') = (rule_id IS NOT NULL))`. The
   dispatcher, backoff and DEAD state are unchanged.
2. **Senders render a `NoticeMessage(title, summary, severity, facts[], url)`:** facts and an "Open
   in Studio" button in Slack and Teams, signed JSON `{type:"notice",…}` on the generic webhook,
   the title as the email subject.
3. **Plugins see channels, never their configuration.** `@PluginApi NotificationChannels.list()`
   returns id, name, kind and enabled.
4. **Plugins enqueue through a scoped `OutboundNotices.enqueue(channelId, notice, dedupeKey)`,** in the
   caller's transaction. The key is required (at most 200 characters) and unique per source: queueing a
   key again is a silent no-op that does not count toward the cap, so a relay that keeps its cursor in
   its own transaction can retry safely. It accepts in-app paths only and makes them absolute from the public URL
   (no public URL, no link), allows 600 notices per hour per source and 8 KB per notice, and is
   absent when alerting is disabled.
5. **Notices never carry a way to act.** An approval notice links to the request; it carries the
   summary, requester, cluster and expiry, never setting or secret values, and a channel can reduce
   it to "a request awaits you" plus the link.

## Consequences

- Notices inherit delivery, retries, signing and dead-lettering already proven for alerts.
- A plugin can relay in the same transaction that advances its own cursor, so a notice is neither
  lost nor sent twice for one event.
- Alert queries must filter by kind; the CHECK keeps the two kinds from mixing.
- Approval links are phishing targets; in-app paths, a Studio-built absolute URL and the hash
  echo on the decision ([ADR-0180](0180-held-operations-are-sealed-claimed-once-and-replayed-as-the-requester.md))
  keep a link from approving anything by itself.

## Alternatives considered

- **A second outbox for notices.** Rejected: it would duplicate the dispatcher, the senders and
  the signing for the same channels.
- **Hand plugins the channel configuration.** Rejected: it exposes webhook secrets and SMTP
  credentials to plugin code.
- **Synthetic alert rules for notices.** Rejected: they would show up as rules operators did not
  write.
