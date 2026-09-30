---
title: Supported broker versions
description: Which Apache Artemis releases Artemis Studio supports, how that range was established, and what happens outside it.
---

# Supported broker versions

| | Artemis release | Image CI tests |
| --- | --- | --- |
| Minimum | **2.33.0** | `apache/activemq-artemis:2.33.0` |
| Latest tested | **2.57.0** | `apache/artemis:2.57.0` |

Every release in between is supported. CI runs the whole integration suite against both ends of the range
on every pull request, not against each release in between.

Artemis became its own Apache project with 2.50: its images moved from `apache/activemq-artemis` (which ends
at 2.44.0) to `apache/artemis`. Studio treats both lines as one broker and compares versions by number.

**ActiveMQ Classic is not supported.** Studio manages Artemis only; Classic has a different management
model, and nothing in Studio talks to it.

## How the range was established

The minimum is the oldest release that has every management operation Studio calls. Reading the broker's
management interface at each release, then running the integration suite against the candidates, shows
that the form of `addSecuritySettings` carrying the *view* and *edit* permissions, which message capture,
plugin taps and configuration edits set, first appears in **2.33.0**. An older broker cannot hold those
permissions, so Studio does not register it rather than set a weaker security setting than it shows.

One operation is newer: the JSON form of `createDivert` arrived in 2.38.0. On an older broker Studio falls
back to the positional form, which carries the same fields, so creating diverts, capture and plugin taps
work across the whole range.

The latest tested release is the newest Artemis release when Studio was built, and it matches the client
library Studio ships.

## What Studio does with a broker's version

Studio reads each node's version when a cluster is registered, and keeps it current while it polls the
node, so an upgraded broker is picked up without re-registering.

| The broker runs | Registration | Afterwards |
| --- | --- | --- |
| Older than 2.33.0 | Refused, naming the minimum. A connection check gives the same answer. | A node found later by topology discovery stays in the cluster and is marked *unsupported release* on the topology. |
| 2.33.0 to 2.57.0 | Accepted. | Everything is available. |
| Newer than 2.57.0 | Accepted, with a warning that the release is untested. | The node is marked *newer release than Studio has tested*. |

A node Studio reaches only through the Core protocol, with no management URL, has no known version and is
never refused on that account.

## Operations that need a newer release

When an operation needs a newer release than a node runs, and Studio cannot fall back, the control stays
visible, disabled, with the release it needs, and the cluster's capabilities list it. In a cluster whose
nodes run different releases, as during a rolling upgrade, the operation runs on the nodes that have it
and each other node is reported as skipped, with the release it needs.

No operation needs this today: everything works from 2.33.0.
