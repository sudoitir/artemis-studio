## Why

A plugin that edits the structure its `DiagramView` draws, such as the steps of a workflow, can change it only from a form beside the diagram. Every insertion, move and removal leaves the picture of what is being changed. The diagram also cannot zoom beyond "fit", and its height is fixed in pixels, so it cannot fill a resizable panel.

## What Changes

- `DiagramView` offers caller-defined **inserts on arrows**: a "+" on edges marked `insertable`, with a menu of `insertChoices`, reported to `onInsert`. Insert on a box offers the same from the keyboard.
- `DiagramView` offers caller-defined **actions on boxes**, from a "⋯", a right-click or Shift+F10, reported to `onNodeAction`. Disabled actions stay listed with their reason.
- Zoom in, zoom out and fit controls; `height` accepts a CSS length.
- The diagram still never changes its nodes itself. ADR-0120 amends ADR-0117. The plugin guide describes the new props.

## Capabilities

### Modified Capabilities
- `operator-ui`: the SDK diagram may offer caller-defined insertions and actions, and zoom.

## Impact

- `web/src/ui/DiagramView.tsx`, `diagram.ts`, `DiagramView.module.css`, `DiagramView.test.tsx`, `web/src/sdk/index.ts`, `site/src/guide/plugins.md`, `docs/adr/0120-*`. There is no new dependency. The props are optional, so the change isn't breaking.
