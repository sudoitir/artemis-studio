# ADR-0005: Frontend on React 19 + Vite + Mantine 9

- **Status**: accepted
- **Date**: 2026-09-03
- **Deciders**: Mahdi Amirabdollahi

## Context

The console needs dense data grids, a topology graph, charts, modals, drawers,
notifications, a command palette, code/JSON viewers — and it must look modern and
dark-mode-first, not like a 2016 admin template. The owner's directive: prefer
battle-tested libraries over building our own.

## Decision

**React 19 + Vite + TypeScript + Mantine 9.**

- **Mantine over shadcn/ui**: shadcn is copied source you then own and maintain —
  ~40 components for this app. The directive rules it out.
- **Mantine over MUI**: MUI's `DataGrid` virtualization, column pinning and tree
  data are MUI X **Pro (paid)**. An OSS project cannot ship a licence trap.
- **Mantine over Ant Design**: AntD reads as enterprise-2016; the brief says
  "fancy".
- **No Tailwind**: Mantine ships a CSS-variable theme and CSS-modules styling;
  a second system solving the same problem is not worth it.

Supporting choices: **TanStack Router** (type-safe, URL owns navigable state),
**TanStack Query** (server state), **TanStack Table** (headless, rendered through
Mantine `Table` — `mantine-react-table` is unmaintained against Mantine 9),
**`@mantine/charts`** (wraps Recharts, inherits the theme), **`@xyflow/react`**
(React Flow — custom React components as topology nodes), **`@mantine/spotlight`**
(⌘K palette).

Tokens are three layers: `web/src/theme.ts` (primitive) → `web/src/theme.css`
semantic `--as-*` vars → components. Logical CSS properties only.

`typescript` is pinned to `5.9.3` for toolchain compatibility (ESLint / Vite /
TanStack); revisit for 7.x once they all declare support.

## Consequences

- No paid tiers, no vendored component tree to maintain.
- Bound to Mantine's theming model; deep visual divergence means component-level
  overrides, not a different system.
- Charts beyond ~20 series / ~50k points need a drop to uPlot — documented, not
  expected at target scale.

## Alternatives considered

Covered above: shadcn/ui + Tailwind (max code ownership), MUI + MUI X (licence),
Ant Design (dated), Mantine (chosen).
