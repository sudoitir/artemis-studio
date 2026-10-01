# ADR-0157: One token scale, read by every component

- **Status**: accepted
- **Date**: 2026-10-01
- **Deciders**: Mahdi Amirabdollahi

## Context

ADR-0005 set three token layers: primitives in `web/src/theme.ts`, semantic `--as-*` variables in
`web/src/theme.css`, then components. Only the colour layer was ever filled in. Spacing, type sizes
and radius were Mantine's defaults, row heights and durations were constants in the components that
used them, and components mixed `--as-*` variables with Mantine colour names (`color="red"`,
`color="pine"`), raw hex values and px literals. A change to one value meant a search, and the
search always missed a copy.

Some numbers are needed in both CSS and JavaScript: the virtualizer needs the row height that CSS
draws, and the navigation width feeds layout code. Kept as two constants, they drift, which is the
row-overlap defect ADR-0020 already fixed once for a single value.

Filled controls had a contrast hack of their own: a rule in `theme.css` that forced Button labels to
a fixed colour. ActionIcon, Badge and Avatar fills never got it, and in the dark scheme a white label
on the primary fill was below 3:1.

## Decision

We will keep the three layers and give every value exactly one source.

- **Primitives in `theme.ts`.**
  - Ten-step colour tuples: graphite (which also becomes Mantine's `gray` and `dark`), cobalt as the
    primary colour, amber and signal (ADR-0158).
  - The type scale, spacing, radius `xs` to `xl` and shadows, all in rem.
  - `other: { density, motion, z, layout }`: row heights and cell padding per density, durations,
    z-indexes and the navigation widths.
- **One `cssVariablesResolver`**, exported from `theme.ts` and passed to `MantineProvider` as a prop,
  emits the numbers CSS and JavaScript both need (`--as-row-h-*`, `--as-cell-pad-*`,
  `--as-duration-*`, `--as-z-*`, `--as-nav-w`). Code in `ui/` and the kernel reads them through
  `useMantineTheme().other`, never by importing `theme.ts`, which keeps the module boundaries
  (ADR-0074).
- **Semantics in `theme.css`.** The `--as-*` colour layer is re-pointed at the new primitives, and
  gains density (`:root[data-density]` selects `--as-row-h` and `--as-cell-pad`), the focus ring
  (Mantine's `.mantine-focus-auto` uses the same ring), easing, the sticky-column shadow, the
  skeleton, and the xyflow control, minimap and background variables mapped to `--as-*`.
- **Reduced motion is a token rule.** Under `prefers-reduced-motion: reduce`, every
  `--as-duration-*` is 0. Components animate only through those durations, so none needs a
  reduced-motion branch of its own.
- **Components read only `--as-*` and `--mantine-*` variables.** No raw colours, no px values apart
  from 1 px hairlines, and no Mantine colour-name props: a component asks for a semantic tone, never
  for "red". A grep check fails the build on a raw colour, a px literal or a colour-name prop in a
  component.
- **`autoContrast`** on the theme, with a tuned `luminanceThreshold`, picks the label colour of every
  filled control. The Button-only label rule is deleted.

## Consequences

- Retuning a colour, a row height or a duration is one edit, and the grep check keeps it that way.
- Reduced motion holds everywhere by construction, including in components written later.
- The virtualizer and the CSS cannot disagree on a row height, because both read the resolver's
  value.
- Every component that used a colour name or a raw value is touched once. That is a large diff with
  no behaviour of its own.
- Plugins see the same variables through the SDK's provider. A plugin that hard-coded the removed
  `pine` colour no longer gets it (ADR-0163).
- The grep check can be too strict for a legitimate value. An exception is an edit to the check, in
  review, not a comment in the component.

## Alternatives considered

- **Move the whole colour layer into TypeScript.** One language for every token, but it rewrites a
  semantic CSS layer whose contrast was measured, for no product gain, and puts a render between a
  theme change and the screen.
- **CSS only, with JavaScript reading computed styles.** `getComputedStyle` on every virtualizer
  measure costs a style recalculation, and a value read before the stylesheet applies is wrong.
- **Keep Mantine colour names at call sites.** They tie a component to a hue rather than a meaning,
  so a palette change becomes a hunt through every feature.
