---
title: MCP server
description: Artemis Studio speaks the Model Context Protocol, so an assistant can answer questions against your real clusters under the same grants and the same audit trail as a person.
---

# MCP server

Studio speaks the [Model Context Protocol](https://modelcontextprotocol.io), so
an assistant can answer *"why is `ORDERS.DLQ` backed up"* against your real
clusters instead of guessing.

The surface is about a dozen **intent-shaped** tools — `cluster_health`,
`diagnose_queue`, `queue_action` — not a mirror of the REST API. A mirror would
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
| Tool | `cluster_health` | HA role per node, split-brain, replication lag, firing alerts |
| Tool | `list_resources` | queues, addresses, consumers, sessions, connections, producers |
| Tool | `diagnose_queue` | one queue end to end: depth, trend, consumers, DLQ, events |
| Tool | `metric_series` | a bucketed timeseries for one metric |
| Tool | `config_diff` | classified configuration differences between two nodes |
| Tool | `browse_messages` / `message_body` | headers, then one body by id |
| Tool | `trace_request_reply` | flows, latency and timeout statistics, configured expectations |
| Tool | `activity_log` | broker events, or Studio's own audit trail |
| Tool | `queue_action` | move / retry / delete / expire / purge |
| Tool | `send_message` | enqueue one message |
| Tool | `alert_rule` / `studio_setting` | alert rules; operational settings |
| Resource | `studio://clusters`, `studio://permissions` | what this key can see and do |
| Resource | `cluster://{id}/topology`, `cluster://{id}/capabilities` | nodes; what the connection supports, with the `broker.xml` to enable what it does not |
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
- **Everything is audited** under the owner with the key's name attached
  (`ada [token: laptop-agent]`), dry runs included.
- **A cluster the key holds no grant on** comes back as *"no such cluster, or
  this key has no grant on it"* — naming no permission and confirming no id.

## Discovery

`studio_help` is a tool, not an optional resource, and one catalogue generates
the schemas, the help text, the resource and the server instructions — so they
cannot disagree ([ADR-0054](/reference/adr/0054-mcp-discovery-is-a-tool-not-a-resource)).
