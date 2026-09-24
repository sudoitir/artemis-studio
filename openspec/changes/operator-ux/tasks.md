## 1. Decision record

- [x] 1.1 ADR-0107..0110, indexed in `docs/adr/README.md`
- [x] 1.2 Library facts verified (Mantine llms.txt, ctx7 TanStack Virtual) and recorded in design.md

## 2. Roving grid (ui)

- [x] 2.1 `ui/rovingGrid.ts` pure model + unit tests
- [x] 2.2 `ui/VirtualTable.tsx`: `label`, single tab stop incl. header, APG keys, widget cells, rangeExtractor + scroll padding, Enter/Space, key-tracked focus, reveal on focus (logical), Ctrl/Cmd+C copy
- [x] 2.3 Pass `label` from every caller; update tests that tabbed into cells

## 3. Row menu, action host, queues

- [x] 3.1 `ui/AnchoredMenu.tsx`, `ui/ActionMenuItem.tsx`, `ui/CapabilityReason.tsx` (CapabilityGate refactored onto it)
- [x] 3.2 Kernel: `ACTION_SECTIONS`, targets, action + link slots, `section` on `SlotContribution`, `ActionHost` in `ClusterLayout`, `ResourceActions`, `ResourceLink`
- [x] 3.3 `kernel/plugins/validate.ts`: section required, guarded quietly, link slots refused; tests
- [x] 3.4 `VirtualTable` `rowMenu`: Actions column, right-click (exemptions), Shift+F10/ContextMenu, close on scroll
- [x] 3.5 Queues: `PauseQueueDialog`, exported `DeleteQueueDialog`, items (open, copy name/link, pause/resume, edit, delete), `queue.link`, off-page `?queue=`, labelled filter, "New queue" gated not hidden
- [x] 3.6 SDK exports (types, `gateFor`, `CapabilityGate`, `ActionMenuItem`)
- [x] 3.7 Tests: host two-phase open + focus restore; keyboard-only menu delete; blocked item explains; row removed → focus to grid

## 4. Other resources and features

- [x] 4.1 Backend: wider filter fields in `PagedListService` + labels; test
- [x] 4.2 Resources: address/consumer/session/connection/producer items, link slots, Close routed through host (outcome survives row); regression test
- [x] 4.3 Routing (divert delete, show in flow), triage (row menu), bulk (purge; one-queue token = name), transfer (transfer messages), metrics (open history), flow (show in flow), messages (browse; per-message items; `?message=`)
- [x] 4.4 `ResourceLink` in grid cells; flow inspector exact links

## 5. Navigation

- [x] 5.1 `useCurrentView`; cluster switch keeps the view (rail + palette)
- [x] 5.2 Title/crumb store, `Breadcrumb`, document title
- [x] 5.3 Palette: compound Spotlight, `query`/`opened` to sources, queue live search, live-resource search actions, permission-aware views, recents, header Search button
- [x] 5.4 Tests: palette, cluster switch, title/breadcrumb

## 6. Shortcuts

- [x] 6.1 Engine, store, help dialog, `NavContribution.hotkey` (unique test)
- [x] 6.2 `useFilterShortcut` adopted by list views (filters labelled)
- [x] 6.3 Settings → Display switch
- [x] 6.4 Tests: `g q`, ignore rules, `?` help round trip, off switch, `code` fallback

## 7. Backend per-node

- [x] 7.1 `MetricSamples` per-node reads; tests
- [x] 7.2 Metrics `splitBy=NODE`, `MetricNodeSeries`, `platform.clusters` dependency; service + controller tests
- [x] 7.3 Flow `byNode`: DTO lists, aggregators; service + controller tests
- [x] 7.4 Regenerate OpenAPI snapshot + `schema.d.ts`; `docs/modules/`

## 8. Flow split

- [ ] 8.1 `flowSearch`: `tab=split`, `node`, `range`; selection from the URL
- [ ] 8.2 `Splitter` layout, persisted sizes, collapsible pane
- [ ] 8.3 `FlowMonitorPane` + `imbalance.ts` (table-driven tests)
- [ ] 8.4 `flow.selection.panels` slot + `metrics/NodeSplitPanels`; ranges moved to kernel
- [ ] 8.5 Navigate-mode menus on graph nodes and table rows
- [ ] 8.6 Metrics view split toggle
- [ ] 8.7 Tests

## 9. Verification and docs

- [ ] 9.1 `just verify` green
- [ ] 9.2 Docs: architecture slot list, flow guide, keyboard guide
- [ ] 9.3 Archive the change, sync specs, tick README roadmap; file follow-ups

## Notes for whoever continues

- Done and pushed: sections 1–7 (the roving grid, row menus on every resource grid, cross-links,
  navigation, shortcuts, and the per-node backend for metrics and flow).
- Still in the Flow row menus (task 8.5): FlowTable rows and graph nodes should render
  `ResourceActions` with `mode="navigate"` (kinds `queue`, `address`, `client`). No `client.actions`
  contribution exists yet; flow should contribute "Focus the view on this" and "Open its connections".
- Frontend types for the breakdown are in `schema.d.ts`: `FlowNodeShare`, `FlowNodeRate`,
  `FlowBrokerNodeView.backlog|consumers|inRate|outRate`, and `MetricNodeSeries` / `MetricSeriesResponse.byNode`.
- Imbalance thresholds live in design.md D11.
