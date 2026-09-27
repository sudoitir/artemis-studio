## Why

A plugin's assistant tools run outside any HTTP route, so nothing guarded them: each tool had to
check its caller's permission itself, and `plugin.json` could not say what a tool needs. One
forgotten line left a tool open, and no install preview, reviewer or model could tell. `studio_help`
also showed plugin tools with no parameter detail. Plugins that let operators edit documents had no
SDK editor and would each bundle their own.

## What Changes

- **BREAKING** Each `mcpTools` entry in `plugin.json` requires `scope` (`cluster` or `global`) and
  `permission` (one of the plugin's permissions), and `posture` is the enum `read | write`. Optional
  `params` carry accepted values, shapes and notes. `Contract.VERSION` goes from 2 to 3.
- Studio checks the declared permission **before the tool runs**: on the cluster named by the tool's
  `clusterId` argument (a denial hidden like Studio's own tools'), or globally (a denial naming the
  permission).
- Activation is refused when registered and declared tools differ, when a cluster tool takes no
  `clusterId`, or when `readOnlyHint` contradicts the posture.
- `studio_help` and `studio://tools` show each plugin tool's permission, scope and parameters.
- A tool declared `write` no longer fails at activation (the posture was mapped to a missing enum
  constant).
- The SDK exports `CodeEditor`, a YAML or JSON editor with located diagnostics.

## Capabilities

### Modified Capabilities
- `plugin-runtime`: plugin assistant tools declare and are held to their permission; discovery
  shows their access; the SDK gains a code editor.

## Impact

- `kernel.plugin` (descriptor, schema, validator, `McpToolDef`), `platform.mcp` (bridge, catalogue),
  `web/src/ui/CodeEditor.tsx` and the SDK, the plugin template and the plugin guide.
- ADR-0114 (tool permissions enforced by Studio), ADR-0115 (CodeMirror YAML and JSON).
- Plugins must be rebuilt for contract 3 and declare `scope` and `permission` for every tool.
