# QA log: sweep after the pages (gate B)
Production build served by Studio, every route at 1920/1440/1280 px in light, dark and system, 200% zoom, and the loading, error, empty, filtered and forbidden states: 1856 captures. The first run failed 388; after the fixes below the second failed 27, of which 15 are for the redesigns and 12 are accepted below.

## Fixed (run 1 → run 2)
- [x] `rr-flows`, `rr-stuck` (every width): grid overflow of 16 px — fixed in `ui/table/measurement.ts` (an empty table and one first drawn in a hidden tab gave its content-sized columns no width; headers are now measured with no rows, a table is measured again on its first rows and when first shown, and a read from a hidden table never counts)
- [x] about 60 routes (zoom, some empty states): layout shift about 0.03 — fixed in the shell: the header is one line that never wraps (`RootLayout.module.css`), the breadcrumb holds its line until the cluster's name is known (`Breadcrumb.tsx`), a table's toolbar holds the pager's height and its end spans the free space (`DataTable.module.css`, token `--as-control-h-xs`), and the freshness word keeps its width (`FreshnessBar.module.css`)
- [x] `enrol-second-factor` (every width): two 403 requests and CLS 0.83 — fixed (the form, which starts an enrolment, opens only once the account is known to need one)
- [x] `admin-users` (zoom): axe target-size on "Grant a role" — fixed for every compact-xs button (24 px, `theme.css`)
- [x] `account` and the enrolment redirect (1920): sections moved as the sessions and API keys loaded — fixed (both lists scroll in place at about eight rows; static tables honour `maxRows`, `ListRows` takes `bounded`)
- [x] `metrics` (empty, zoom): charts moved down 40 px when the window note arrived — fixed (one-line note; bucket sizes read "5m", not "PT5M")
- [x] `admin-*` (error, forbidden): not settled — not a finding: loaders inside hidden, kept-mounted tabs; the sweep now counts only visible ones
- [x] `configuration-recommended` (zoom): code block scrolls sideways — not a finding: code at 200% zoom may scroll in two dimensions (WCAG 1.4.10, ADR-0164); the sweep exempts zoom from grid overflow

## Accepted
- [x] `audit`, `events`, `alerts-firing`, `alerts-history` (zoom): CLS 0.014 to 0.048 as columns fit their first rows — accepted: the columns are measured from the headers while loading and fit once to the first rows, as the spec requires ("measured on the first non-empty data"); at 1280 px and wider the change stays under 0.01

## For the redesigns (PR C)
- [ ] `sql` (12 captures): axe scrollable-region-focusable on the editor pane
- [ ] `topology` (error, 3 captures): not settled
