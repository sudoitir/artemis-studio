## How to run this change
This change is run as three pull requests, each leaving the product working:

1. **Foundation**: tokens and visual identity, the data table, the shared page parts, the shell, every grid call site, the SDK, browser tests and the QA sweep.
2. **Pages**: every feature page rebuilt on the shared parts.
3. **Redesigns**: the SQL console and Topology, cleanup, documentation and screenshots, then `/opsx:archive`.

Each pull request runs `just verify` and the full screenshot sweep (1920, 1440 and 1280 px; light, dark and system schemes; every state) before it opens, and merges on green CI with no open Sonar issues.

## Why
The console grew one screen at a time. About 40 screens render their own tables, 7 write their own empty state, over 100 hand-roll an error box, and spacing and type are library defaults. The one shared grid sizes its columns wrongly: it measures each column at its already stretched width, so most grids scroll sideways at 1280 to 1440 px and never shrink back. Operators read dense live state under pressure; inconsistent tables, illegible identifiers and grids that need sideways scrolling slow them down at the worst moment.

## What Changes
- A new visual identity on one token scale for type, spacing, colour, radius, elevation, motion and density, with bundled, self-hosted typefaces chosen for telling identifiers apart.
- One data table for the whole console, with an interactive virtualised grid and a static table for small read-only sets. Columns size to their content and fit the window from 1280 px; when they cannot, the table truncates, then hides the least important columns and says so, and only then scrolls with its identity and action columns kept in view.
- Table density (compact or comfortable), chosen by the viewer.
- Shared page parts: page header with one heading per page, sections, toolbars, empty, error and loading states, status badges, figures, description lists and a confirmation dialog.
- The colour scheme follows the operating system until the operator picks light or dark.
- Every page rebuilt on these parts. The SQL console and Topology are redesigned.
- **BREAKING** (plugin SDK): `VirtualTable` and `GridColumn` are replaced by `DataTable` and `Column`, and the `pine` theme colour is removed. The plugin contract version goes up, so plugins built against the old SDK are refused with a clear message instead of failing at render.
- **BREAKING**: remembered column widths reset once; the SQL console's saved column choice resets once.
- Layouts for narrow windows are removed. The console is designed for desktop windows from 1280 px and stays usable at 200% zoom.

## Capabilities
### New Capabilities
- none
### Modified Capabilities
- `operator-ui`: grid sizing and overflow, reuse of the shared table and page parts, loading and failure states, the colour scheme control; new requirements for density, the desktop layout floor and zoom, one heading per page, the SQL console workspace and the Topology view.

## Out of scope
- Saved, named or shared table views (a later change persists the table state this change introduces).
- Layouts for phones and tablets.
- Translating the console's UI text.

## Execution
**Workflow**: the work fans out per page group behind a shared foundation, with a screenshot-review-fix loop per group.

## Impact
The whole web console (`web/src`), the plugin SDK and the plugin template, the plugin contract version, the CI frontend job (browser tests), the QA tooling (an isolated compose overlay, a seed script and a screenshot sweep), the site guides for plugins, keyboard use and the SQL console, and the README screenshots.
