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
- Status: fixed (`#boot-status` keeps its id, role and text and is `position: fixed; inset: 0` with the text centred, so React replacing it shifts nothing)

### shell-kernel-ui-cls-2 [S2 · design · page] The cluster list pushes the navigation down when it loads
- Where: `web/src/features/clusters/ClusterRail.tsx` (the `shell.navbar` slot)
- Evidence: a 0.13 layout shift on every cluster route: the cluster rows and "Register cluster" arrive after the navigation and push it 51 px down; with 12 clusters the navigation starts far below the fold.
- Fix: a cluster switcher of constant height (current cluster and environment in one row, opening a searchable list with registration), so the navigation never moves and scales to many clusters.
- Status: fixed (`ClusterSwitcher` replaces the rail in `shell.navbar`: one box of one height showing the cluster's name, environment with its colour, health in words with an icon and node count, or "Choose a cluster"; it opens a Mantine Combobox with a search, grouped by environment, with "Register cluster" last, and keeps the current view on a switch. Loading, outside a cluster, 1 and 30 clusters, expanded and collapsed are measured at the same height in `ClusterSwitcher.browser.test.tsx`, which also runs axe in both schemes with the list open. The registration form and its xyflow canvas load when the dialog first opens)

### shell-kernel-ui-react-1 [S2 · reliability · page] setState while rendering
- Where: `FreshnessBar` updated while `RegisterClusterButton` renders
- Evidence: React "Cannot update a component while rendering a different component" on cluster routes.
- Fix: move the update into an effect or derive it.
- Status: fixed (the cause was `useFreshness` setting state on every query-cache event, including `added` and `observerResultsUpdated`, which TanStack Query fires while a component renders its `useQuery`. It now syncs only on the events that change what it reads: `updated`, `removed`, `observerAdded`, `observerRemoved`. `ClusterSwitcher.test.tsx` fails with the warning when that filter is removed)

## Sweep findings after the pages (account)

Found by the full sweep (`.sweep/final`): `shell/account` 1920 dark default, 1280 light error, 1920 light error and 1920 dark error, and `identity-local/enrol-second-factor` 1920 light default, all layout shift. Reproduced with the narrowed sweep (`--only account,enrol-second-factor`) against a Studio that includes the fixes: 0 of 56 captures fail.

### shell-kernel-ui-cls-3 [S2 · performance · page] The account page moves when its sections load or fail

- Where: `web/src/features/identity-local/TwoStepSection.tsx`, `web/src/features/security/SessionsManager.tsx`, `web/src/features/apitokens/ApiKeysPanel.tsx` through `web/src/ui/table/DataTable.tsx`
- Evidence: three sections load on their own and each swapped a loading frame for content of another height. Measured in Chromium at 1920 x 1080: the two-step section loaded 28 px shorter than its `22rem` frame; the sessions list loaded 116 px taller than its `6rem` frame (and 17 sessions in the QA seed make it 30rem); a failed section was about 200 px shorter than its loading frame, so the sections below were pulled up by up to 0.02 of the viewport. The 0.01 budget failed whichever of the three queries landed last, which is why only some widths and runs showed it.
- Fix: each section's loading frame and its failure hold the same height as the loaded content. Two-step: `20.25rem`, the section for an account with an authenticator app and recovery codes. Sessions: the list is a region of a fixed `12rem` (about four rows, scrolling past that), so the frame is that plus the button under it, whatever the number of sessions. `ErrorState` takes `blockSize`, and a static `DataTable` that never had rows holds the height of its eight loading rows when it fails (`StateSlot` `reserveRows`).
- Status: fixed (`SessionsManager.shift.browser.test.tsx` and `TwoStepSection.shift.browser.test.tsx` measure what follows each section across loading to one, a few and 17 sessions, to the loaded status and to a failure; `DataTable.browser.test.tsx` holds a failed static table to the height of its loading rows; `ErrorState.browser.test.tsx` holds `blockSize`. Each fails without the fix.)

### shell-kernel-ui-cls-4 [S3 · test harness · not a product defect] `enrol-second-factor` is the account page for the QA seed's admin

- Where: `web/scripts/sweep/routes.ts` (`identity-local/enrol-second-factor`)
- Evidence: the page opens only for an account that must enrol a second factor and has none. The seed's admin has one, so the console redirects to `/account` and the capture measures the account page (its shift was the same sessions and two-step shift as above, 0.0084 in one run, over the budget in another).
- Fix: none to the product. The shift went with shell-kernel-ui-cls-3. The enrolment form itself is measured by `identity.browser.test.tsx` and by the sweep once the seed has an admin without a factor.
- Status: fixed (the capture passes with the account page's shifts gone)

### shell-kernel-ui-sweep-1 [S3 · test harness · not a product defect] A stopped sweep recorded the capture in flight as an exception

- Where: `web/scripts/sweep.ts` (`resources/connections` 1440 light default, `exception`)
- Evidence: "browserContext.newPage: Target page, context or browser has been closed". Playwright closes the browser when the sweep process gets SIGINT or SIGTERM, so the capture in flight failed and no `report.json` was written; the connections route has no error of its own (the narrowed sweep passes it at every width, scheme and state).
- Fix: the sweep launches Chromium with `handleSIGINT`, `handleSIGTERM` and `handleSIGHUP` off, stops taking captures on a signal, lets the ones in flight finish, writes the report of what it has and exits 1.
- Status: fixed (a SIGTERM during a run printed "finishing the captures in flight", wrote `report.json` with the 2 captures taken and exited 1)
