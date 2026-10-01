# ADR-0163: Pages are built from shared page parts

- **Status**: accepted
- **Date**: 2026-10-01
- **Deciders**: Mahdi Amirabdollahi

## Context

Every page built its own frame. There were seven local empty states, about 118 inline red `Alert`s
for failures, about 25 ad-hoc `Title order={3}` headings, no shared loading state, and 47 direct
`notifications.show` calls. Cluster pages skipped from the cluster header's h1 to h3. A failure read
"Request failed" whether the cause was a missing permission, an unreachable node or a rate limit. An
empty table said "No data" whether nothing existed, a filter excluded everything, or a node could
not be reached. Plugins, which build pages with the SDK, had none of this to reuse.

## Decision

We will build every page from one set of parts in `web/src/ui/`, all exported by the SDK.

| Part | What it is |
|---|---|
| `Page` | The page frame; `fill` gives the last child the remaining height. |
| `PageHeader` | The page's single h1, with description, meta and actions. |
| `Section` | A titled block (h2 or h3), plain or as a card. |
| `Toolbar` | The row of controls above a view. |
| `EmptyState` | A union: `empty`, `filtered` (requires `onClearFilters`), `unreachable` (requires `nodes`). It says what the resource is, why there is none and the next action. |
| `ErrorState` | Reads `ApiError` by its shape and maps network failure, 401, 403 (naming the permission), 404, 409, 422 (listing the fields), 429 (giving the retry time), 5xx and broker error kinds to a cause and a next step, with retry where it helps. |
| `LoadingState` | The loading frame, at the size of what it stands for. |
| `StatusBadge` | A state, always in words. |
| `Stat` | A figure; `null` renders "Unavailable", never 0. |
| `DescriptionList` | Term and value pairs. |
| `ConfirmDialog` | Confirmation, embedding `ConfirmByTyping` for destructive actions. |
| `notify` | One toast helper, announced through `aria-live`. |

- Each page has exactly one h1, from `PageHeader`. The cluster header becomes a context strip beside
  it (cluster, environment and freshness), not a second heading.
- A check fails the build on `Title order`, or on a red `Alert` outside `ErrorState`, in feature
  code.
- **SDK.** `VirtualTable` and `GridColumn` are removed; `DataTable` (ADR-0160), `Column`,
  `ColumnKind`, `RowMenu` and every part above are added; the `pine` colour is removed (ADR-0158).
- **The extension contract goes up by one** (`Contract.VERSION` and the SDK's `CONTRACT`, 8 to 9).
  The SDK is a shared singleton that plugins import at runtime. Without the bump, an installed plugin
  that imports `VirtualTable` would pass validation, because its contract number still matches, and
  then crash when its page renders. With the bump, it is refused at load with the existing "built for
  extension contract N" message, and its page is never shown broken.
- **The exported API is frozen per release.** Every merge to `main` is a release (ADR-0126), so a
  later change to these exports is its own breaking commit with a migration note, never a silent edit.
- The plugin template moves to the new parts.

## Consequences

- An empty view teaches, a failure names its cause and next step, and loading holds the space its
  content will take, on every page and in every plugin that uses the parts.
- Heading structure is correct by construction, and assistive technology can navigate by heading.
- A fix to a part is a fix to every page.
- Every plugin built for contract 8 stops loading until it is rebuilt against the new SDK. That is
  the intended failure: a clear refusal instead of a crash at render. The breaking-change note in the
  SDK commit gives the migration: `VirtualTable` to `DataTable`, `width` to `kind`, `priority`, `min`
  and `max`, `compact` to `height={{ maxRows }}`, `emptyLabel` to `empty={<EmptyState … />}`, a
  required `label`, and the primary colour in place of `pine`.
- The SDK surface grows, and each part is now public API with the same compatibility weight as any
  other export.

## Alternatives considered

- **Keep `VirtualTable` as an alias of `DataTable`.** Its props differ (`width`, `compact`,
  `emptyLabel`), so an alias would need a translation layer that keeps the old shape alive, while the
  contract bump already gives an old plugin a clear refusal.
- **Change the SDK without a contract bump.** Plugins would load and crash at render.
- **Parts for built-in pages only.** Plugin pages would keep the inconsistencies, and the operator
  sees one console.
- **Leave headings and states to each page.** That is the state being replaced.
