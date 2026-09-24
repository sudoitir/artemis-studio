# ADR-0105: Email, Teams and PagerDuty notification channels

- **Status**: accepted
- **Date**: 2026-09-24
- **Deciders**: Artemis Studio maintainers

## Context

ADR-0036 made alert delivery durable: a (rule, channel, tick) row in
`alert_delivery`, claimed with `SKIP LOCKED`, retried with backoff, and sent by a
`NotificationSender` per channel kind. It shipped two kinds, Slack and signed webhooks.

On-call rotations page through PagerDuty, or through a tool that accepts its
Events API v2. Many organisations run Microsoft Teams rather than Slack. Email is
the destination of last resort. Operators without one of these have to poll the
Alerts screen, which is not alerting.

Three facts shape how each kind is sent:

- **PagerDuty** needs one event per incident. A delivery row carries every transition
  of a tick.
- **Teams** is retiring Office 365 connector webhooks in favour of Workflows webhooks.
  Both accept an Adaptive Card message.
- **Email** needs an SMTP client, which the JDK does not have.

## Decision

We will add three channel kinds on the existing delivery path, with no change to how
rows are queued, claimed or retried.

- **PagerDuty** posts one Events v2 event per transition in the row: `trigger` or
  `resolve`, with `dedup_key` = SHA-256 of `artemis-studio|ruleId|subject`. A retry
  resends the whole row. PagerDuty deduplicates on the key, so a retry needs no
  per-event ledger. The endpoint is set per channel, and defaults to
  `https://events.pagerduty.com/v2/enqueue`. The routing key is the channel's secret.
- **Teams** posts an Adaptive Card message to the webhook URL, which is the channel's
  secret.
- **Email** uses **Jakarta Mail through `spring-boot-starter-mail`**. A
  `JavaMailSenderImpl` is built per delivery from the channel's configuration, and
  the SMTP password is the secret.
  - STARTTLS, when chosen, is *required* (`mail.smtp.starttls.required`), and the
    server identity is checked.
  - Timeouts come from `alerting.email-timeout`.
- **All kinds share `AlertMessageFormatter`**, so they state the same facts.
- **The delivery payload gains fields** (the cluster, readable subject labels, times,
  counts, and a Studio link built from `alerting.public-url`). It removes and renames
  none, so existing webhook receivers are unaffected.

## Consequences

- One new runtime dependency (Jakarta Mail, through the Boot starter). Its
  auto-configured global `JavaMailSender` backs off without `spring.mail.host`, and
  Studio does not use it: each channel carries its own server.
- A PagerDuty delivery can partly succeed and then be resent in full. That is safe
  only because of the deduplication key, and is recorded here so that nobody
  "optimises" the key away.
- A channel's destination can be any `http(s)` host the operator names, including an
  internal one. Restricting destinations belongs to egress policy, not Studio. The
  notification client follows no redirects.
- There is still no per-channel routing (severity filters, quiet hours). If it is
  wanted, it is a separate decision.

## Alternatives considered

- **One PagerDuty event per row, keyed on the rule.** It cannot resolve a single
  subject, and one queue recovering would close an incident that other queues still
  hold open.
- **A per-transition delivery row for PagerDuty.** This breaks ADR-0036's "one row per
  tick per channel", and multiplies rows by subject count for no gain the
  deduplication key does not already give.
- **A hand-written SMTP client, or a shelled-out `sendmail`.** Both re-implement TLS,
  authentication and MIME badly.
- **Teams MessageCard.** This is the legacy connector format. Workflows webhooks do
  not render it.
