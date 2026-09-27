# ADR-0115: The SDK code editor uses CodeMirror's YAML and JSON language packages

- **Status**: accepted
- **Date**: 2026-09-27
- **Deciders**: Mahdi Amirabdollahi
- **Change**: `openspec/changes/plugin-mcp-tool-access`

## Context

Plugins increasingly let an operator edit a document (a definition, a request body), and the SDK
offered no editor, so each plugin would bundle and style its own. Studio already ships CodeMirror 6
for the SQL console (`features/sql/QueryEditor.tsx`), coloured from `theme.css`.

## Decision

We add `@codemirror/lang-yaml` and `@codemirror/lang-json`, and depend directly on
`@codemirror/lint`, which was already installed. With them we export `CodeEditor` from the SDK: a
YAML or JSON editor with line numbers, a lint gutter, diagnostics passed in by line and column (the
server is the validator), a live-region summary of those diagnostics, and a read-only mode. The
colours move into `ui/codeMirrorTheme.ts`, shared with the SQL editor.

## Consequences

- Plugins get the console's editor without bundling CodeMirror: the SDK component resolves to the
  host's copy at runtime.
- Two small official CodeMirror packages join the bundle, and they follow the CodeMirror release
  line Studio already tracks.
- The editor does no validation or completion of its own. A plugin that wants schema completion
  would need more, and that is a separate decision.

## Alternatives considered

- **Monaco.** Heavier, a second editor stack, and harder to theme from `theme.css`. Rejected.
- **A plain `Textarea`.** It has no line numbers or located diagnostics, and those are the point of
  editing a document. Rejected.
