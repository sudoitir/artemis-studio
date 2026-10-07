# QA: cluster onboarding screens

Sweep: `web/.sweep/before/onboarding/register/*` and `web/.sweep/after/onboarding/register/*` (1280, 1440,
200% zoom; light, dark and system), against an isolated stack registered from one seed URL. The check found
both brokers of the pair from that one URL, each with the management account accepted.

### register-1 [S2 · design · page] The node check hides the facts it exists to show
- Where: `web/src/features/clusters/nodeProbeColumns.tsx`, the register form and the Connection settings
- Evidence: seven columns in a form column about 640 px wide; the solver hid four of them (role, management
  URL, version, NodeID: "Columns 4"), and the headers sat apart from their badges.
- Fix: three columns, each with a dimmed line of detail: the node (role, version, NodeID), the management
  account (its result, or what to do about it, and the URL with its source), the Core account (its result
  and what to do about it).
- Status: fixed (unit tests in `features/clusters`; the after captures show every fact, wrapped in its
  column, in light, dark and at 200% zoom).

### register-2 [S3 · design · page] A rejected account does not say what to do
- Where: the node check's account cells
- Evidence: `core-rejected` shows a bare "Rejected" badge.
- Fix: a rejected account says "Check the Core account's username and password."; an unreachable one says
  to check the address and the network.
- Status: fixed (same change as register-1).

### register-3 [S3 · design · page] Each recommendation buries its one change in seventeen unchanged keys
- Where: `web/src/features/brokerconfig/RecommendedConfiguration.tsx`, `ValuePreview`
- Evidence: the register and configuration screens list every key of the entry, each marked "(unchanged)",
  which doubles the page's height and hides the key being changed.
- Fix: the changed keys are listed; the keys written back as they are wait behind one disclosure that counts
  them ("Show the 17 keys it keeps as they are").
- Status: fixed (`RecommendedConfiguration.test.tsx`).

### register-4 [S3 · performance · page] A check's result moves the form's buttons
- Where: the actions of the register form and the Connection settings
- Evidence: CLS 0.0225 (1280) and 0.0245 (core rejected): the result arrives after the click's 500 ms
  window and pushes Check connection and Register cluster down a page that is over 2,000 px tall.
- Fix: the actions of both long forms are sticky at the window's bottom edge, on the surface token with a
  top border, and a focused field keeps a scroll margin so the bar never hides it (WCAG 2.4.11).
- Status: fixed (after sweep: CLS 0 at 1280, at most 0.0088 at 200% zoom).

### register-5 [S4 · design · page] "URL" written as "url" in the URL source
- Where: `connectionWords.ts`, `managementUrlWords`
- Evidence: "(derived from the management url pattern)".
- Fix: only the first letter of the source is lowered.
- Status: fixed.

Not a finding: the sweep's scroller check flags the `broker.xml` fragment (`XmlBlock`), which scrolls
sideways on purpose and is a named, focusable region, as `operator-ui` requires.

Not a finding: under Vite's dev server React logs a development-only `use()` warning; the production
before sweep had no console error.

## After

- `onboarding/register`: 15 captures (1280 light and dark, 200% zoom), every check passes.
- `brokerconfig`, `sql`, `clusters`, `settings` (default and scenes, 1280 and 200% zoom, on a cluster
  registered from one seed URL with adoption on, holding names past 120 and 200 characters and 250
  messages): 63 captures, every check passes.
