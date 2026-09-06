<!--
Hand-written release notes for the next version, spliced in above the entries
generated from commit messages. This file is OPTIONAL and usually absent: a
commit body is the better place for a note, because it cannot drift from the
change it describes. Use this only for something no single commit can say — a
`### Breaking` migration step that several commits add up to, or an upgrade
warning about the release as a whole.

CI moves it into `changelog/<version>.md` and deletes it. See
.claude/rules/05-commits.md.
-->

### Added

- **Queues and addresses can now be created, destroyed, reconfigured, paused and
  resumed from Studio**, without dropping to the `artemis` CLI or a JMX console. A
  command names a *cluster*, not a node: Artemis cluster nodes each own their own
  queues, so the operation fans out to every live node and the result is a per-node
  outcome rather than a single yes or no. A node that was not live is reported as
  skipped, never as a failure, and re-running after a partial application converges.
  Nothing is rolled back on a partial failure — a destroyed queue cannot be restored,
  and a compensating create would be a different state dressed up as the original —
  so the divergence is reported and left in your hands.
- **Every lifecycle command has a preview.** `?dryRun=true` names the target nodes
  and, for a destroy, the messages that would be lost on each, without touching any
  broker. Destroying a queue destroys its messages, so it is counted against the same
  `safety.bulk-cap` as any other bulk destructive operation and needs the same
  explicit override above it.
- **Four new permissions** — `queue:create`, `queue:delete`, `queue:update` and
  `queue:pause` — grantable globally, per environment, or per cluster. Holding
  `queue:purge` does not imply any of them: emptying a queue and destroying it are
  different authorities. They appear in the role editor with no upgrade step.
- **One MCP tool, `queue_lifecycle`**, covering every kind. It previews by default,
  and turning the preview off for a destructive kind additionally requires a
  confirmation matching the target's name.
- **A node's effective broker configuration is now readable on its own** — the
  settings it is actually running with, as the broker resolves them — at
  `GET /api/v1/clusters/{id}/nodes/{nodeId}/config`, and to an MCP client as the
  `cluster://{id}/nodes/{nodeId}/settings` resource. Previously configuration could
  only be seen by diffing two nodes against each other.
- A paused queue is now identifiable as paused in the cluster's queue view,
  including when it is paused on some nodes and not others.

### Changed

- **`managementWrite` is no longer inferred from a read.** It was reported as
  available whenever a read-only `listNetworkTopology()` call succeeded, which proves
  only that Jolokia is not under a read-only policy — not that any particular
  operation is permitted. It is now **unknown** until a management write has actually
  been attempted on that connection, **available** once one has succeeded, and
  **unavailable** once one has been refused for an authorization reason, with the
  `broker.xml` that would grant it.

  A connection previously shown as `AVAILABLE` on inference alone will now read
  `UNKNOWN` until its first write. **This is a correction, not a regression** — the
  previous value was not evidence-backed. Write operations are still offered while it
  is unknown, with the uncertainty stated: absence of evidence must not block you.
  A write that fails for any other reason — an unreachable broker, an argument the
  broker rejects — leaves the assessment alone, so one bad request cannot permanently
  disable a button.
- **Warning and danger text is legible in the light theme.** Measured against the
  WCAG 2.2 AA floor rather than assumed: warning text was 2.13:1 and danger text
  3.84:1 on white, both under the 4.5:1 minimum for body text. They are now 6.67:1
  and 5.46:1, keeping their hue. Purely graphical marks — chart axes, graph edges,
  the alert dot — are unchanged, since the text floor does not apply to them.
- The MCP tool listing budget now scales with the number of tools rather than being a
  fixed ceiling, and enum values and JSON body shapes moved out of the tool schemas
  into a `studio://tools` resource that a client reads only when it needs them
  ([ADR-0050](../docs/adr/0050-mcp-progressive-disclosure.md)). No tool was removed.
