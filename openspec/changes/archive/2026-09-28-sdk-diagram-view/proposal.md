## Why

Plugins can show tables, code and charts in Studio's style, but not a structure of boxes and arrows, such as the steps of a workflow. They may not import Studio's graph library, so today they either bundle their own, with a different look and no layout worker, or they fall back to a list.

## What Changes

- The plugin SDK exports `DiagramView`: a read-only diagram of plain nodes and edges. It is laid out by the ELK worker Studio already uses, drawn with xyflow in Studio's theme, and operable by keyboard. Problems are stated in words. ADR-0117.
- A plugin guide section describes it.

## Capabilities

### Modified Capabilities
- `operator-ui`: the SDK offers a read-only diagram.

## Impact

- `web/src/ui/DiagramView.tsx`, `web/src/sdk/index.ts`, `site/src/guide/plugins.md`, `docs/adr/0117-*`. There is no new dependency, and the change isn't breaking.
