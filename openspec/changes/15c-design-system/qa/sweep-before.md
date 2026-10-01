# QA log: baseline sweep findings by area

Machine checks from the baseline sweep (`npm run sweep -- --label before`), by sweep area. Each is fixed by the unit that owns the area, or by the base unit when it repeats across areas. `net::ERR_NETWORK_CHANGED` errors were a network change on the capture machine, not the console, and are not findings.

### sweep-1 [S2 · a11y · page] Contrast below AA
- Where: alerts (rules), audit, events, messages (DLQ queue), settings (SQL index)
- Fix: the new token scale; the contrast test covers every token; recheck these routes.
- Status: open (audit and events: fixed in the views, no coloured badges, colour-name props or colour-name text left, so every colour is a token; recheck both routes in the after sweep. The other routes belong to their own units)

### sweep-2 [S2 · a11y · base] ARIA attribute not allowed on its role (`aria-prohibited-attr`)
- Where: admin (data), flow (map and table), identity-local (enrol second factor), rr (expectations), settings (health, security), account
- Fix: find the shared element (likely a labelled non-interactive element) and use a role that allows the label or a visible heading.
- Status: open

### sweep-3 [S2 · a11y · base] Scrollable region not focusable (`scrollable-region-focusable`)
- Where: brokerconfig (config diff), identity-local (enrol second factor), metrics, account, sql
- Fix: a scroll container gets a label and `tabIndex=0`, or content that is focusable; the DataTable's scrollers do this by design.
- Status: open

### sweep-4 [S3 · a11y · base] Link in text block distinguished by colour only (`link-in-text-block`)
- Where: admin (plugins), brokerconfig (declared), rr (flows)
- Fix: inline links are underlined (InlineLink and the anchor token).
- Status: open

### sweep-5 [S2 · a11y · page] Focusable element inside an aria-hidden subtree (`aria-hidden-focus`)
- Where: shell (home)
- Status: open (not in the shell's files. On a home with no cluster the page is `RegisterCluster`, whose example cards wrap `TopologyCanvas` in an `aria-hidden` box; React Flow's nodes take focus by default (`nodesFocusable`), so they sit focusable inside it. `TopologyCanvas` should pass `nodesFocusable={interactive}` and `edgesFocusable={interactive}`. Unconfirmed in a browser: the after sweep decides)

### sweep-6 [S2 · security · base] The CSP blocks an `eval` on some pages
- Where: admin (data), alerting (rules), flow
- Evidence: `securitypolicyviolation` script-src blocked `eval` on these routes.
- Fix: find the code that evaluates strings (a dependency or our own) and replace it, or load it so it does not need eval; never loosen the CSP.
- Status: open

### sweep-7 [S3 · reliability · page] The sign-in page asks for signed-in data
- Where: shell (login)
- Evidence: `/api/v1/auth/me` is expected to be 401 there, but `/api/v1/manifest` and `/api/v1/time` are requested and refused with 401, logging console errors.
- Fix: do not request signed-in data before sign-in.
- Status: open

### sweep-8 [S3 · performance · base] The main bundle has chunks over 500 kB
- Where: the production build (`vite build` warning)
- Fix: route-level code splitting so each view loads its own code.
- Status: fixed (every feature's views, and the shell's Admin and Account pages, load as route chunks through `lazyFeatureView` and TanStack's `lazyRouteComponent`, preloaded on intent; the slot contributions that pull in recharts, xyflow or elkjs load through `lazySlot`. Entry chunk `index-*.js` 868.24 kB (262.55 kB gzip) down to 317.33 kB (95.06 kB gzip): 63% smaller raw, 64% gzip. The CodeMirror chunk is 1,083.54 kB down to 373.55 kB (the view code that rode along is now in the view chunks); recharts is its own 363 kB chunk, loaded by the metrics and flow views only. Three chunks stay over 500 kB, none of them application code and none in the entry: `@mantine/core` 677 kB (the shared singleton, whole by design, see `vite.config.ts`), shiki's `wasm` 622 kB and `elk.bundled` 1,431 kB (single-file third-party bundles, fetched only when a view needs code highlighting or a graph layout without a worker). The build still prints the 500 kB warning for those three. Open: xyflow (165 kB) is still preloaded with the entry, because the cluster rail's register dialog imports `RegisterCanvas` statically (`features/clusters/RegisterCluster.tsx`))
