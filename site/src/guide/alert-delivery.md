---
title: Alert delivery
description: Send alerts to Slack, Microsoft Teams, PagerDuty, email or a signed webhook — each firing described in words, retried until delivered, and a delivery log that says why one failed.
---

# Alert delivery

An alert nobody receives is a log line. A rule in **Alerts** can route its firings and
resolutions to any number of **notification channels**, which you manage in
**Settings → Notification channels**. Channels are global: one channel usually serves
several clusters.

| Kind | Where it goes | What you need |
|---|---|---|
| **Slack** | An incoming webhook | The webhook URL |
| **Microsoft Teams** | A Workflows webhook (“When a Teams webhook request is received”); legacy connector webhooks work too | The webhook URL |
| **PagerDuty** | Events API v2 — US or EU region, or any compatible receiver | The integration’s routing key |
| **Email** | Any SMTP server | Host, port, security, sender, recipients; a password if it needs one |
| **Signed webhook** | Your own receiver | A URL and a signing secret |

Secrets (a webhook URL, a routing key, an SMTP password, a signing secret) are encrypted
at rest and never shown again. When you edit a channel, leave the secret blank to keep
the stored one.

## What a notification says

Every channel states the same facts, taken from one formatter:

- the severity **in words** (`CRITICAL`, `WARNING`, `INFO`), the rule and the cluster;
- each subject in readable form, such as `node primary-1` or `queue orders`;
- the observed value and the time of each transition;
- a link back to the cluster’s alerts, when `ARTEMIS_STUDIO_PUBLIC_URL` is set.

One evaluation that fires fifty queues sends **one** notification listing all fifty,
never fifty notifications.

### PagerDuty incidents open and close with the alert

Each firing subject becomes one PagerDuty incident. Its `dedup_key` is derived from the
rule and the subject, so:

- the subject resolving sends a `resolve` for that same key, and the incident closes;
- a delivery that is retried after a partial failure opens no second incident.

A test sends a trigger and its resolution together, so it leaves no incident open.

### Email

STARTTLS, when chosen, is **required**: a server that does not offer it fails the delivery
instead of receiving the alert in clear. Implicit TLS (usually port 465) and plain SMTP are
also available. The subject is always one line, so a rule name cannot add a mail header.

### Signed webhooks

Deliveries follow [Standard Webhooks](https://www.standardwebhooks.com/): the
`webhook-id`, `webhook-timestamp` and `webhook-signature` headers over the raw body. The
id is stable across retries of one delivery, so use it to deduplicate. The payload:

```json
{
  "event": "alert.transitions",
  "version": 2,
  "ruleId": "…", "ruleName": "Split-brain", "severity": "CRITICAL",
  "clusterId": "…", "clusterName": "prod-eu",
  "studioUrl": "https://studio.example.com/clusters/…/alerts",
  "firedCount": 1, "resolvedCount": 0,
  "transitions": [
    { "subject": "cluster", "subjectLabel": "cluster prod-eu", "kind": "FIRED", "value": 1, "at": "2026-09-24T10:00:00Z" }
  ]
}
```

Version 1 receivers keep working: every earlier field is still there, with the same
meaning.

## Test before you save

**Send a test** in the editor delivers a test notification with the configuration as
typed — nothing has to be saved first. The result says whether it arrived, and if not,
what the receiver or the network said and whether retrying could help. Tests are audited.

## When delivery fails

Each channel row shows how its last delivery went, when, its last error, and the last
24 hours’ sent and failed counts. A channel that has been failing is visible without
opening it.

- A failed delivery is retried with growing delays (**Settings → Operational
  configuration → Alerting**), honouring a receiver’s `Retry-After`.
- A failure retrying cannot fix — a revoked Slack webhook, a deleted Teams workflow, a
  wrong routing key, rejected SMTP credentials — stops at once, as **failed**.
- The **delivery log** (the history icon) lists the newest 100 deliveries: attempts, last
  error and times. After fixing a channel, **Retry** puts a failed delivery back on the
  queue.

Deleting a channel tells you how many rules route to it, and asks you to type its name.
Those rules keep firing and recording history, and deliver to their other channels.
