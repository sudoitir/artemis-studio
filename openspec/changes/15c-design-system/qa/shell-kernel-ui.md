# QA log: shell-kernel-ui

Every finding is fixed before this change is archived. Severity: S1 blocks a task or fails AA; S2 broken or misleading; S3 inconsistent; S4 polish.

## Code audit (before)

### shell-kernel-ui-1 [S2 · reliability · base] A second stream's cleanup clears the global stream status while the cluster stream is still live

- Where: `web/src/kernel/stream/useClusterStream.ts:216`
- Evidence: Each useClusterStream mount writes the same module-level `current` status through publish(). ClusterLayout.tsx:24 always mounts one stream, and EventsView.tsx:142 mounts a second one while the live feed is on. When EventsView unmounts, or live is switched off (topicKey becomes '' and the effect tears down), cleanup runs publish(null). The header freshness indicator then shows 'no live stream here' even though ClusterLayout's EventSource is still open. It stays wrong until that stream next changes state, which happens only on a failure or a reopen, so it can be minutes. While both streams are mounted they also overwrite each other: one stream's 'reconnecting' can hide the other's 'live', and the reverse.
- Fix: Make the status store reference-counted or keyed per mount. For example, keep a Map<symbol, StreamStatus>, publish the worst status across it, and have cleanup delete only its own entry. Alternatively, let a secondary stream with an onFrame callback leave the shared status alone.
- Status: fixed (each stream owns one entry in the store and the header reads the worst of them; cleanup removes only its own. Two tests in `useClusterStream.test.tsx`: a second stream unmounting leaves the cluster's status, and the worst status wins)

## Screenshot sweep (before)

Baseline: 830 captures (default state at 1920/1440/1280 in light, dark and system plus 200% zoom; loading, error, empty, filtered-empty and forbidden at 1280). 541 failed: 319 layout shift, 195 stored light scheme not applied, 70 axe, 17 console errors, 17 failed requests, 4 CSP violations, 4 not settled, 1 page overflow.

### shell-kernel-ui-sweep-1 [S2 · design · token] A stored light scheme is not applied on load

- Where: `web/index.html`, `web/src/main.tsx`
- Evidence: 195 light captures render dark: the page is forced dark before React and Mantine applies the stored scheme only after mount, so it is lost in a race.
- Fix: first-paint `boot-prefs.js` resolves the stored or system scheme before React; `defaultColorScheme="auto"`.
- Status: fixed (verified: `index.html` loads `boot-prefs.js` synchronously, `main.tsx` passes `defaultColorScheme="auto"`, and `bootPrefs.test.ts` covers stored light, stored dark, auto in both system schemes and a first visit)

### shell-kernel-ui-sweep-2 [S2 · performance · base] Pages shift as they load

- Where: every route (319 captures over the 0.01 budget)
- Evidence: loaders and late content push the page down; fonts swap without metric-matched fallbacks.
- Fix: loading states at the content's size, preloaded fonts with metric overrides, density applied before first paint.
- Status: open
### shell-kernel-ui-test-1 [S3 · reliability · base] ShortcutsHelp popover test is flaky

- Where: `web/src/kernel/keyboard/ShortcutsHelp.test.tsx:14`
- Evidence: `findByRole('dialog', { name: 'Keyboard shortcuts' })` timed out after 8.6 s in one of three runs (once in the full suite, once alone), passing in the others.
- Fix: find why the popover can take longer than the wait to appear (its transition, the tooltip on the same trigger) and make the test deterministic.
- Status: fixed (the popover hid itself when floating-ui judged its zero-size anchor detached; `hideDetached={false}`, and focus now moves onto its switch, which the test asserts)

## Screenshot review (after wave 3)

### shell-kernel-ui-cls-1 [S2 · performance · base] The boot placeholder moves the page when the app replaces it
- Where: `web/index.html` (`#boot-status`)
- Evidence: a 0.19 layout shift on every route: the placeholder sits in the flow with a 30vh margin, so the body jumps when React replaces it.
- Fix: take the placeholder out of the flow (fixed, centred over the viewport).
- Status: open

### shell-kernel-ui-cls-2 [S2 · design · page] The cluster list pushes the navigation down when it loads
- Where: `web/src/features/clusters/ClusterRail.tsx` (the `shell.navbar` slot)
- Evidence: a 0.13 layout shift on every cluster route: the cluster rows and "Register cluster" arrive after the navigation and push it 51 px down; with 12 clusters the navigation starts far below the fold.
- Fix: a cluster switcher of constant height (current cluster and environment in one row, opening a searchable list with registration), so the navigation never moves and scales to many clusters.
- Status: open

### shell-kernel-ui-react-1 [S2 · reliability · page] setState while rendering
- Where: `FreshnessBar` updated while `RegisterClusterButton` renders
- Evidence: React "Cannot update a component while rendering a different component" on cluster routes.
- Fix: move the update into an effect or derive it.
- Status: open
