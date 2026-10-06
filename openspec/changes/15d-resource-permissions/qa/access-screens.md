# Access screens: visual QA (task 5.6)

Isolated stack `artemis-studio-qa-15d`, production jar, seeded with the Orders (`orders.#`) and Billing
(`billing.#`) teams, a share of `billing.invoices.#` to Orders as Team Viewer, and the users `admin`,
`orders-admin`, `orders-viewer` and `billing-op`. Captured at 1440 x 900, light and dark, as the administrator
and as team-only users: teams list (default, empty, error), team tabs, role editor with a team role, grant
dialog, access check drawer, queues list (default, empty, error, selection, row menu), queue detail with the
Access panel, create-queue dialog, API-key mint form with a pattern. Each capture ran axe (WCAG 2.2 AA),
overflow, CLS, CSP, console and failed-request checks.

### access-1 [S2 · reliability · base] A refused optional request turned the header to Offline
- Where: `kernel/shell/useFreshness.ts`
- Evidence: a team member's every page showed "Offline" because one observed query answered 403 or 404.
- Fix: a 403 or 404 means Studio answered, so it no longer counts as offline.
- Status: fixed (`FreshnessBar.test.tsx` holds 503 as offline and 403 and 404 as not).

### access-2 [S2 · reliability · page] Team members asked for environments and firing alerts they may not read
- Where: `features/clusters/api.ts` (`useEnvironments`), `features/alerting/api.ts` (`useFiringAlerts`)
- Evidence: 403 `GET /environments` on every cluster page, 404 `GET /alerts/firing` on the topology, both console errors.
- Fix: the queries wait for `environment:read` and for the caller's access to the cluster.
- Status: fixed (views' tests serve `holding(...)`).

### access-3 [S2 · design · page] Owner chip clipped its team name
- Where: `features/queues/columns.ts`, `features/resources/columns.tsx`
- Evidence: "Orders" and "Billing" cut at the chip's border; the column was measured from the bare name.
- Fix: `badge: true`, so the Measurer adds the chip's padding and border.
- Status: fixed.

### access-4 [S2 · design · page] Owner chip led a team member to a "team does not exist" page
- Where: `kernel/auth/OwnerChip.tsx`
- Evidence: clicking Billing as `orders-viewer` opened Administration with Not found.
- Fix: links only for a `user:admin` holder or that team's admin; otherwise the name alone.
- Status: fixed (`OwnerChip.test.tsx`).

### access-5 [S3 · design · base] A description pushed one input box below its neighbour's
- Where: `ui/FieldRow.module.css`; pattern, share and access-check forms
- Evidence: Cluster select 581 to 616 px, Pattern input 596 to 631 px (description above it).
- Fix: inside a row the description sits below the box and Mantine's gap above the box is removed.
- Status: fixed (`FieldRow.browser.test.tsx`, a description case in both schemes).

### access-6 [S3 · design · base] A row menu drew a heading for a section with nothing in it
- Where: `kernel/actions/ResourceActions.tsx`
- Evidence: "Destroy" with no items for a Team Viewer, and a stray divider above it.
- Fix: each section is a group that is not drawn without a menu item; dividers lie between drawn groups.
- Status: fixed.

### access-7 [S2 · design · base] A rejected address moved the fields below it
- Where: `features/queues/AddressPicker.module.css`
- Evidence: Queue name label 217 px valid, 204 px with the message, touching the message; the message was out of flow and the reserved line was dropped.
- Fix: a labelled picker takes its message in flow like every other field.
- Status: fixed (recapture: 217 px both ways; no browser test, the picker needs the queues API).

### access-8 [S2 · a11y · token] A toast's close button had no name (axe `button-name`, critical)
- Where: `theme.ts`
- Fix: `Notification` default `closeButtonProps` label.
- Status: fixed.

### access-9 [S2 · a11y · token] Hovered menu item held 3.1:1 in dark (axe `color-contrast`)
- Where: `theme.css`
- Fix: `--menu-item-hover` takes the default hover step in dark.
- Status: fixed.

### access-10 [S2 · a11y · base] Row menu anchor had aria-expanded and aria-haspopup on a role-less span (axe `aria-allowed-attr`, critical)
- Where: `ui/AnchoredMenu.tsx`
- Fix: `role="button"`.
- Status: fixed.

### access-11 [S3 · a11y · page] Permission group checkboxes were under 24 px (axe `target-size`)
- Where: `features/security/PermissionPicker.tsx`
- Fix: size md.
- Status: fixed.

### access-12 [S3 · design · page] Share and member selects showed an empty box
- Where: `features/security/TeamShares.tsx`, `TeamMembers.tsx`
- Fix: placeholders, as the pattern form's cluster select has.
- Status: fixed.

### Open, not fixed
- access-13 [S1 · reliability · server] A team-only user cannot mint a key: `GET /permissions` needs `user:admin`
  (`RoleService.catalogue`), so the mint form's catalogue is empty and it says "You hold nothing at this scope" at
  every scope. Needs a decision on who may read the permission catalogue; no UI change can fix it.
- access-14 [S4 · design · page] The Team admin notice on the Teams tab arrives after the access summary and shifts
  the table by CLS 0.013 for a non-administrator (budget 0.01).
