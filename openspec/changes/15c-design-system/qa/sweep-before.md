# QA log: baseline sweep findings by area

Machine checks from the baseline sweep (`npm run sweep -- --label before`), by sweep area. Each is fixed by the unit that owns the area, or by the base unit when it repeats across areas. `net::ERR_NETWORK_CHANGED` errors were a network change on the capture machine, not the console, and are not findings.

### sweep-1 [S2 · a11y · page] Contrast below AA
- Where: alerts (rules), audit, events, messages (DLQ queue), settings (SQL index)
- Fix: the new token scale; the contrast test covers every token; recheck these routes.
- Status: open

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
- Status: open

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
- Status: open
