## 1. Studio info

- [x] 1.1 `StudioInfo` `@PluginApi` bean: `version()` from `StudioVersion`, `clusterName(UUID)` empty without a grant; unit tests; plugin guide section

## 2. Code editor

- [x] 2.1 `--as-code-property` and `--as-code-meta` tokens in both schemes, with their measured contrast
- [x] 2.2 Highlight mapping for the lezer YAML and JSON tags; folding, bracket matching and closing, indent on input, active line, selection matches, search, Tab indent with the Escape-then-Tab hint; `maxHeight`, `lineWrapping`, horizontal scroll inside the editor
- [x] 2.3 Vitest: distinct classes for a key, a value and a comment; folding; Escape then Tab leaves; plugin guide updated

## 3. Grid columns

- [x] 3.1 ADR-0116; ADR-0108 links to it
- [x] 3.2 `VirtualTable`: TanStack column sizing and resizing, fit on first render and column change, drag handle, double-click fit, Ctrl+Shift+Arrow resize with announcement, `storageKey` persistence that ignores bad or stale entries
- [x] 3.3 A stable `storageKey` on every built-in grid
- [x] 3.4 Vitest for fit, keyboard resize and storage; Playwright drag and alignment; screenshots light and dark

## 4. Ship

- [ ] 4.1 `just verify` green; PR; merge on green CI; release
