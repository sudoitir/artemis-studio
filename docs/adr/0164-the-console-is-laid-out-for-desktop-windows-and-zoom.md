# ADR-0164: The console is laid out for desktop windows and for zoom

- **Status**: accepted
- **Date**: 2026-10-01
- **Deciders**: Mahdi Amirabdollahi

## Context

Studio is a desktop console (ADR-0034). Its layouts nevertheless carried small-screen code that was
never designed or tested: `visibleFrom` and `hiddenFrom` props, responsive `cols` and `span` objects,
and px-width `@media` queries in a few feature stylesheets. None of it produced a usable phone or
tablet layout, and each one was a second layout to keep correct.

What does matter on a desktop is zoom. Operators zoom to read, and WCAG 2.2 AA requires text to
resize to 200% (1.4.4) and content to reflow without loss (1.4.10). A layout tuned to px breakpoints
by device class does not respond correctly to zoom: at 200% zoom a 1280 px window is 640 CSS pixels
wide and should behave like one.

At a 1280 px window at 100% zoom, the content box beside the expanded navigation is about 960 px.

## Decision

We will design for desktop windows from 1280 px, and make zoom work through intrinsic layout.

- **Design targets:** 1920, 1440 and 1280 px wide at 100% zoom. No page scrolls horizontally in a
  1280 by 800 window with the navigation expanded.
- **No phone or tablet layouts.** Every responsive prop, responsive `cols`/`span` object and px-width
  media query is removed, and a check fails the build on a new one.
- **Intrinsic layouts.** Pages wrap by content, not by breakpoint: wrapping flex,
  `repeat(auto-fit, minmax(<n>rem, 1fr))` grids and `minmax(0, 1fr)` tracks. Forms, headers,
  toolbars, dialogs and figure rows wrap on their own, so they hold at 200% zoom.
- **One zoom accommodation:** the navigation collapses to its existing icon rail when the inline size
  is below `64rem`. The threshold is in rem, so it reacts to zoom, not to device class, and it reuses
  the existing collapsed state.
- **Two-dimensional exceptions (WCAG 1.4.10).** Data grids, graph canvases and the code editor may
  scroll in both directions; their meaning depends on two-dimensional layout. Data grids still
  degrade in their own stated order first (ADR-0161).
- Viewport-height panes use `100dvh`, and scroll containers reserve their scrollbar gutter.

## Consequences

- One layout to design, test and screenshot per width, instead of several half-built ones.
- Zoom to 200% stays usable with no content or function lost, which the UI sweep checks at 1280 px
  and 200% (ADR-0165).
- A phone shows the desktop layout, which may need zooming out. That is a deliberate scope choice
  for an operations console.
- Intrinsic layouts are harder to reason about than a breakpoint table: a page's shape depends on
  its content. The sweep's long-content fixtures exist to catch that.

## Alternatives considered

- **Keep and finish the responsive layouts.** A large design and test surface for devices this
  console is not used on.
- **px breakpoints for zoom.** They match device classes, not zoom levels, and a zoomed desktop window
  would land in a layout designed for a different device.
- **A minimum page width with horizontal page scroll below it.** Fails reflow at 200% zoom.
