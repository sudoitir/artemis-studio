# ADR-0114: Plugin MCP tools declare their permission, and Studio enforces it before they run

- **Status**: accepted
- **Date**: 2026-09-27
- **Deciders**: Mahdi Amirabdollahi
- **Change**: `openspec/changes/plugin-mcp-tool-access`
- **Builds on**: [ADR-0045](0045-mcp-server-is-a-capability-surface.md), [ADR-0046](0046-mcp-authenticates-with-existing-api-tokens.md), [ADR-0054](0054-mcp-discovery-is-a-tool-not-a-resource.md), [ADR-0099](0099-runtime-plugins-are-child-contexts-installed-from-the-ui.md)

## Context

A plugin's `@McpTool` beans join Studio's MCP server through `McpPluginBridge`. A tool is called
outside any HTTP route, so no `@PreAuthorize` guards it. Until now each tool had to check the
caller's permission itself, as the template's `NotesTools` did with `ClusterAccessGuard`. That
put a plugin's whole authorization story one forgotten line away from an open tool, and nothing
could see it: `plugin.json` declared a tool's name and posture but not what it needs, so neither
the install preview, `studio_help` nor a reviewer could tell an unguarded tool from a guarded one.
`studio_help` also showed no parameter detail for plugin tools at all, because the descriptor had
nowhere to put it.

The bridge also mapped the declared posture with `Posture.valueOf(posture.toUpperCase())` against
an enum of `READ` and `MUTATE`, so a tool declared `write`, the other documented value, failed at
activation.

## Decision

**Every plugin MCP tool declares `permission` and `scope` in `plugin.json`, and Studio checks
them before the plugin's code runs.**

- `permission` is one of the plugin's own declared permissions. `scope` is `cluster` or `global`.
  Both are required, and `posture` is now the enum `read | write`. `params` (`name`, `values`,
  `shape`, `note`) are optional and reach `studio_help` and `studio://tools` as the tool's detail,
  with its permission and scope.
- In `McpPluginBridge`, before `runInPlugin`, a `cluster` tool's `clusterId` argument is parsed
  (a missing or malformed one is -32602) and checked with `ClusterAccessGuard.requireCluster`.
  A denial is the same hidden answer every built-in tool gives (`McpErrors.CLUSTER_DENIED`), so the
  cluster's existence is not confirmed. A `global` tool is checked with `PermissionResolver.can`,
  and a denial names the permission, as ADR-0045 requires where there is no id to hide.
- Activation is refused when the registered tools and the declared tools differ, when a `cluster`
  tool takes no required string `clusterId`, or when a tool's `readOnlyHint` contradicts its
  posture. A host gates a tool on that hint (ADR-0054), so a mismatch misleads the operator.
- The declared permission is the floor. A plugin still checks finer rules inside its services.
- The descriptor now requires fields it did not before, so `Contract.VERSION` goes from 2 to 3 and
  plugins built for contract 2 are refused. There is no compatibility path.

## Consequences

- A plugin tool cannot be open by omission. What each tool needs is visible in `plugin.json`, in
  `studio_help` and to the key's owner, and it is enforced by the same code that guards Studio's
  own cluster-addressed tools.
- A tool that needs a permission on something other than a cluster or the whole installation (an
  environment, say) cannot express it yet. It declares the broader scope and checks the rest
  itself, and a later ADR can add a scope.
- Every plugin must be rebuilt against contract 3 and declare `scope` and `permission` for each tool.

## Alternatives considered

- **Keep per-tool checks and document them better.** It relies on every author remembering, and
  nothing sees a miss. Rejected.
- **Filter `tools/list` by the key's permissions.** ADR-0054 already rejected hiding capabilities
  behind the caller's grants. The check belongs at the call.
- **Infer the scope from the presence of a `clusterId` argument.** It is implicit, a renamed argument
  silently changes a tool's authorization, and the descriptor cannot show it. Rejected for an explicit field.
