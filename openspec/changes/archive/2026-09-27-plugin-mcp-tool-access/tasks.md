## 1. Contract

- [x] 1.1 `plugin.json` `mcpTools[]`: `scope` and `permission` required, `posture` an enum, optional `params`; `PluginValidator` refuses an undeclared permission; `Contract.VERSION` 3 (template, web `CONTRACT`)
- [x] 1.2 One posture mapping (`PluginDescriptor.McpTool.toCatalogueEntry`) for the bridge and `FeatureRegistry`, fixing `write`

## 2. Enforcement and discovery

- [x] 2.1 `McpPluginBridge`: refuse undeclared tools, a cluster tool without `clusterId`, and a posture that contradicts `readOnlyHint`; nothing is catalogued when activation is refused
- [x] 2.2 Check the declared permission before the plugin runs: cluster through `ClusterAccessGuard` (hidden), global through `PermissionResolver` (named); a malformed `clusterId` is -32602; a plugin tool's own -32602 reaches the caller as is
- [x] 2.3 `McpToolDef.Access`; `studio_help` index and topic and `studio://tools` show permission, scope and params

## 3. SDK

- [x] 3.1 `CodeEditor` (YAML/JSON, line numbers, lint gutter, diagnostics by line and column, live-region summary, read-only), theme shared with the SQL editor, exported from the SDK

## 4. Docs and tests

- [x] 4.1 ADR-0114, ADR-0115; plugin guide "Assistant tools"; template `NotesTools` without its own check
- [x] 4.2 `PluginMcpAccessIT`, validator tests, `CodeEditor.test.tsx`; existing plugin fixtures declare scope and permission
- [x] 4.3 `just verify` green; PR; merge on green CI; archive into `openspec/specs/`
