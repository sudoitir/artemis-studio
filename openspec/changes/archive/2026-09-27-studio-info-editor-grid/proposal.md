## Why

Three gaps show up as soon as a plugin moves documents between environments, and two of them hurt every operator:

- A plugin can't read which Studio it runs on or the name of a cluster, so anything it exports can't say where it came from.
- The code editor barely highlights YAML. Keys and plain values render as body text, so a document reads as one grey block. It has no folding, bracket matching or search, and a long line spills out of a narrow container such as a drawer.
- Data grids cut long values with an ellipsis. Free-text columns share whatever space is left over, and a column can't be widened. Queue names, addresses and client ids, exactly the values an operator came to read, are the ones that get cut.

## What Changes

- **Studio info for plugins.** A new `@PluginApi` bean, `StudioInfo`, gives the running Studio version (empty for a development build) and the display name of a cluster the current caller holds a grant on.
- **The code editor.**
  - The SDK `CodeEditor` highlights YAML and JSON keys, values, comments, punctuation, document markers, anchors and tags from two new colour tokens (`--as-code-property`, `--as-code-meta`), measured at 4.5:1 or more in both schemes.
  - It folds, matches and closes brackets, indents on input, highlights the active line and the selection's other matches, and searches (Ctrl-F).
  - Tab indents. Escape then Tab leaves the editor, and the editor says so.
  - It takes an optional `maxHeight` and an opt-in `lineWrapping`. Long lines scroll inside the editor.
- **Grid columns.**
  - Every `VirtualTable` column fits its content when rows first render (up to a cap) and can be resized: by dragging its header border, by double-clicking it (fit to content), or with Ctrl+Shift+Left/Right on a focused header cell. The new width is announced.
  - A grid given a `storageKey` remembers widths per viewer. Every built-in grid has one.

## Capabilities

### New Capabilities
- None.

### Modified Capabilities
- `plugin-runtime`: the Studio info API; the richer code editor.
- `operator-ui`: grid columns fit and resize.

## Impact

- An additive `@PluginApi` type; `Contract.VERSION` stays 3.
- `web/src/ui/CodeEditor.tsx`, `codeMirrorTheme.ts` and `theme.css` (two tokens), using only the CodeMirror packages already shipped. The SQL console shares the highlight style and gains dimmed punctuation.
- `web/src/ui/VirtualTable.tsx` keeps viewer-set widths in a map of its own (TanStack's resize handler starts from a model size, not the rendered track), and takes an optional `storageKey`. Built on ADR-0107 and ADR-0108; decided in ADR-0116.
