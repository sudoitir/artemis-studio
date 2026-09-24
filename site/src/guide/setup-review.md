---
title: Setup review
description: Studio checks each cluster's HA, clustering, durability and message-safety configuration against known mistakes — a single replication pair that cannot win a quorum vote, redistribution left off, a connector advertising localhost — and shows the evidence and the broker.xml fix.
---

# Setup review

The Artemis mistakes that hurt most stay silent until the failover that exposes them.
**Configuration → Setup review** checks each cluster against a catalogue of them, and
shows every finding with:

- what is wrong, and what it costs you;
- the evidence: what each node actually reported;
- the fix, as `broker.xml` you can copy.

The review is read-only. It makes **one batched read per node** every 15 minutes, and
whenever you press **Review now**. It never writes to a broker.

## The classic: a single replication pair

The most common setup of all is one primary and one backup, with replication on quorum
voting. It is also a split-brain waiting to happen.

With only one primary, nobody is left to vote. Artemis skips the vote
(`AMQ221083: ignoring quorum vote as max cluster size is 1`), and the backup promotes
itself whenever it loses its primary. On a network partition the primary is still running
on the other side, so both serve clients and their journals diverge.

Studio reports this as **critical** and offers:

- a **distributed lock manager** (ZooKeeper) to coordinate the pair; or
- **three or more primary/backup pairs**, so a real majority exists; and
- as a mitigation, a **network pinger** (`network-check-list`), so a node that loses the
  network stops instead of promoting itself.

Studio cannot see whether a pinger is configured: the management API does not expose it.
The finding says so. If you have one, accept the finding as a known risk (see below).
With exactly two primaries the review warns instead, because one surviving primary
decides every vote.

## What else it checks

| Area | Examples |
|---|---|
| High availability | A primary with no backup; a pair whose halves use different HA policies; a node with no HA in a multi-node cluster |
| Clustering | A node outside the cluster connection; a stopped cluster connection; a member a node’s cluster connection does not see; a connector advertising `localhost`; load balancing off; `max-hops` 0; **redistribution left at its default of `-1`**, which strands messages on a node whose consumers leave; mixed broker versions |
| Durability | Persistence off; unbounded disk usage |
| Message safety | No dead-letter address (poison messages are dropped); unlimited redelivery; no expiry address; `DROP` when an address is full |
| Security | Security disabled; acceptors without TLS |

Severity is fixed per rule, and always shown in words:

- **Critical**: data can be lost, duplicated or diverge under a foreseeable event.
- **Warning**: messages can be stranded, or service degraded.
- **Info**: a hardening step.

Where the fix is an address setting, the finding links to **Broker configuration**, which
can apply it canary-first.

## What the review cannot see

- **A node that did not answer** is named, with the reason. Its earlier findings are kept
  and marked *not re-checked*, not resolved. Cluster-wide rules (quorum, version skew)
  run only when every live node answered.
- **Settings the management API does not expose** (`network-check-list`, `quorum-size`,
  `vote-on-replication-failure`) are named in the findings that depend on them.
- **“No open findings” is not proof of a sound setup.** It means none of the rules found
  a mistake in what the nodes reported.

## Accepting a risk

A finding can be **accepted as a known risk**, for example the single pair on a
development cluster. An acceptance:

- needs a reason, and can expire (7, 30 or 90 days) or last until revoked;
- is audited, with who accepted it and why;
- keeps the finding on screen under *Accepted as known risks*.

Acceptance needs `alert:write` on the cluster, because it also silences the alert.

## Alerting on it

Create a state rule for **Setup risk**: the rule form offers a template. It fires once per
open critical or warning finding that has not been accepted, and resolves when the finding
is fixed or accepted. A finding about a node that did not answer stays firing: an
unreachable node cannot resolve a risk. Route the rule to a channel, as described in
[Alert delivery](./alert-delivery).

## For an AI assistant

The MCP tool `setup_review` returns the latest review of a cluster, read-only, under the
same `cluster:read` permission. It never starts a review.
