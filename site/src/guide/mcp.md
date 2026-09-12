---
title: MCP server
description: Artemis Studio speaks the Model Context Protocol, so an assistant can answer questions against your real clusters under the same grants and the same audit trail as a person.
---

# MCP server

Studio speaks the [Model Context Protocol](https://modelcontextprotocol.io), so
an assistant can answer *"why is `ORDERS.DLQ` backed up"* against your real
clusters instead of guessing.

The surface is sixteen **intent-shaped** tools — `diagnose`,
`message_action`, `broker_config_change` — not a mirror of the REST API. A mirror would
spend the model's context on plumbing and leave it to assemble the diagnosis;
the tools are shaped like the questions instead
([ADR-0045](/reference/adr/0045-mcp-server-is-a-capability-surface)).

## Get a key

Sign in → avatar menu → **Account** → **API keys** → **New key** → choose the
scope and permissions it carries → copy the value. It is shown once.

## Connect

`POST /mcp`, on the same origin as the UI, with the key as a bearer token.

```json
{
  "mcpServers": {
    "artemis-studio": {
      "type": "http",
      "url": "https://studio.example.com/mcp",
      "headers": { "Authorization": "Bearer as_..." }
    }
  }
}
```

Smoke-test it without a client:

```bash
curl -s https://studio.example.com/mcp \
  -H "Authorization: Bearer as_..." \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

## What is there

| Kind | Name | For |
|---|---|---|
| Tool | `studio_help` | the catalogue itself: every tool, its posture and its parameters |
| Tool | `diagnose` | a cluster (HA role per node, split-brain, replication lag, firing alerts) or one queue end to end |
| Tool | `list_resources` | queues, addresses, consumers, sessions, connections, producers, diverts, bridges |
| Tool | `metric_series` | a bucketed timeseries for one metric |
| Tool | `config_diff` | classified configuration differences between two nodes |
| Tool | `broker_config` | the cluster's declaration, its drift per node, the `broker.xml` fragment, or past applies |
| Tool | `browse_messages` | headers, or one body by id |
| Tool | `trace_request_reply` | flows, latency and timeout statistics, configured expectations |
| Tool | `activity_log` | broker events, or Studio's own audit trail |
| Tool | `message_action` | move / retry / delete / expire / purge |
| Tool | `queue_lifecycle` | create, update, pause, resume or destroy a queue, address or divert |
| Tool | `broker_config_change` | declare a configuration, or apply it canary-first with hazards acknowledged by id |
| Tool | `connection_action` | close a connection, session, consumer or an address's consumers |
| Tool | `send_message` | enqueue one message |
| Tool | `alert_rule` / `studio_setting` | alert rules; operational settings |
| Resource | `studio://clusters`, `studio://permissions`, `studio://tools` | what this key can see and do |
| Resource | `cluster://{id}/topology`, `cluster://{id}/capabilities`, `cluster://{id}/nodes/{nodeId}/settings` | nodes; what the connection supports, with the `broker.xml` to enable what it does not; one node's effective settings |
| Prompt | `triage_cluster`, `investigate_queue`, `before_you_purge`, `tune_scrape_load` | runbooks |

## The safety contract

An assistant with a management API is exactly where a safety model needs to be
boring and explicit. This one is:

- **A key never exceeds its owner.** Grants are intersected with the owner's
  *live* grants on every call, so narrowing a person narrows their keys at once
  ([ADR-0046](/reference/adr/0046-mcp-authenticates-with-existing-api-tokens)).
- **Mutations dry-run by default.** A real destructive run additionally requires
  `confirm` to equal the queue's own name — separate from the bulk-cap
  `override`, which answers a different question and is never satisfied by
  `confirm`.
- **A configuration apply is the same contract at cluster scale.** The dry run
  returns the plan, its hazards and the exact `acknowledge` ids a real run must
  echo; the real run needs `confirm` to equal the cluster's name and
  `expectedPlanHash` to equal the hash it previewed, goes canary first and halts
  at the first failure. See [Broker configuration](/guide/broker-configuration).
- **Everything is audited** under the owner with the key's name attached
  (`ada [token: laptop-agent]`), dry runs included.
- **A cluster the key holds no grant on** comes back as *"no such cluster, or
  this key has no grant on it"* — naming no permission and confirming no id.

## Discovery

`studio_help` is a tool, not an optional resource, and one catalogue generates
the schemas, the help text, the resource and the server instructions — so they
cannot disagree ([ADR-0054](/reference/adr/0054-mcp-discovery-is-a-tool-not-a-resource)).
