---
layout: home
title: Artemis Studio
titleTemplate: One console for every Artemis cluster
head:
  - - meta
    - name: description
      content: Open-source, cluster-wide management and observability for Apache ActiveMQ Artemis — live/backup topology, cross-node queues, safe message operations, request-reply tracing, and SQL over your messages.

hero:
  name: Artemis Studio
  text: One console for every Artemis cluster
  tagline: Live topology, cross-node queues, safe message operations, and SQL over your messages — across every Apache ActiveMQ Artemis cluster you run, from one instance.
  image:
    src: /img/topology.png
    alt: Live/backup topology across a cluster
  actions:
    - theme: brand
      text: Quickstart
      link: /guide/quickstart
    - theme: alt
      text: What it is
      link: /guide/
    - theme: alt
      text: GitHub
      link: https://github.com/sudoitir/artemis-studio

features:
  - title: The whole cluster, not one broker
    details: Live/backup pairs, replication state and a shared-NodeID axis on one canvas. HA role is polled from every node, never read from config — two live in a pair is a split-brain alert, not a surprise at 3am.
    link: /guide/
  - title: Every queue, every node, one table
    details: Queues, addresses, consumers, sessions, connections and producers across the estate in a single virtualised grid, updating over SSE. Sort by depth and the worst thing in the cluster is the first row.
    link: /guide/
  - title: SQL over your messages
    details: "\"Where did order 4471 go?\" is one query across every queue in the cluster — including the body, which a JMS selector cannot see. The cost of the query is shown before it runs."
    link: /guide/sql-console
  - title: Destructive operations you can trust
    details: Every mutating call takes ?dryRun=true and answers with the affected count without acting. Purge and delete need the queue's name typed. Everything is audited in the same transaction as the command.
    link: /guide/
  - title: Request-reply tracing
    details: Correlate a request to its reply across addresses and nodes, with latency and timeout statistics against expectations you declare. Broker clocks are normalised onto Studio's, and the skew is disclosed.
    link: /guide/
  - title: An MCP server over the same grants
    details: Ask an assistant why ORDERS.DLQ is backed up and have it answer against your real clusters — through a dozen intent-shaped tools, under the same permissions and the same audit trail as a person.
    link: /guide/mcp
---

## See it working

![Artemis Studio: topology, the cross-node queue grid, and a dead-lettered message](/img/demo.gif)

A real session against a real cluster pair: the topology, the cross-node queue
grid, and a message that went to the dead-letter queue.

## Install it in one command

```bash
git clone https://github.com/sudoitir/artemis-studio && cd artemis-studio
just up          # Studio + Postgres, secrets generated, pinned to the latest release
```

Your brokers already exist — Studio registers them, it does not start them. It
works against an unmodified `broker.xml` beyond the management endpoints you
almost certainly already run. [Full quickstart →](/guide/quickstart)

::: warning Alpha
Under active development and not yet feature-complete. Published images are
pre-stable dev builds (`sudoit1/artemis-studio:dev`, no `:latest` yet). Expect
breaking changes.
:::
