# ADR-0158: A visual identity for reading data, with bundled hyperlegible typefaces

- **Status**: accepted
- **Date**: 2026-10-01
- **Deciders**: Mahdi Amirabdollahi

## Context

Studio is an operations console for Apache Artemis brokers. Its readers are SREs, often on call,
who read dense live state and take actions that cannot be undone. What they read most are
identifiers: queue and address names, message ids, client ids, node names. Many differ only in
characters that common UI fonts draw alike: `0` and `O`, `1`, `l` and `I`, `rn` and `m`.

The theme named Inter, but Inter was never loaded. Every machine rendered whatever its system
substituted, so text widths, and with them column widths, differed between machines. The palette
used a `pine` primary and Mantine's defaults elsewhere.

Fonts cannot come from a CDN. Studio runs on installations without internet access, and its
Content-Security-Policy allows fonts only from its own origin (ADR-0122, `font-src 'self' data:`).

## Decision

We will make legibility of data the one strong choice, and keep everything else quiet.

- **Typefaces.**
  - **Atkinson Hyperlegible Next** for UI text and identifiers. It separates `0/O`, `1/l/I` and
    `rn/m`. Numbers, times and figures use its tabular digits (`tabular-nums`).
  - **Atkinson Hyperlegible Mono** for code and message bodies.
  - Identifiers stay in Next, not Mono: Mono is 15–25% wider, and identifier columns would not fit
    a 1280 px window (ADR-0164).
- **Shipping them.**
  - They are pinned npm dependencies (`@fontsource-variable/atkinson-hyperlegible-next` and
    `-mono`). Vite bundles the `woff2` files into the build with hashed names, so they ship inside
    the jar and the image and are served by Studio from its own origin. No font, script or style is
    requested from another host.
  - The two Latin files are preloaded from `index.html`.
  - The fallback faces carry `size-adjust`, `ascent-override` and `descent-override`, matched to
    the real fonts, so swapping in the real face does not move the layout.
  - A check fails the build on a reference to a font CDN, and the UI sweep fails on any request to
    another origin (ADR-0165).
- **Palette.**
  - **Graphite**: near-monochrome surfaces, borders and text.
  - **Cobalt**: the one accent, for the primary action, focus, selection and links.
  - **Amber** for degraded, lagging or at risk; **signal** red for failed, down or destructive.
  - `pine` is removed. Chart series are re-pointed so none collides with the accent.
  - Exact steps are fixed by a contrast test: AA in both schemes on every surface (4.5:1 for text,
    3:1 for controls and non-text marks).
- **Health is quiet.** A healthy state is the default, stated in neutral text and a word, not in
  green. Colour is spent only where something needs attention, and a state is always stated in
  words, never by colour alone. A user-chosen environment colour is always paired with its name.

## Consequences

- An identifier reads the same on every machine, and a column measured on one machine has the same
  width on another, which column fitting depends on (ADR-0161).
- Studio renders identically on an air-gapped installation, with no CSP change.
- The build carries the font files, about two Latin `woff2` files on first paint. Other subsets load
  only when a page uses their characters.
- Every screenshot in the README, the site and the guides changes and must be regenerated.
- Two font packages are now dependencies whose updates are reviewed like any other.
- Operators who learned the old colours relearn them once. A quiet healthy state means a busy page
  draws the eye only to what is wrong, which is the intent.

## Alternatives considered

- **Keep Inter, and finally load it.** It fixes the substitution, but Inter does not separate the
  characters identifiers depend on as clearly.
- **A monospace face for identifiers.** Unambiguous, but too wide for the columns at a 1280 px
  window.
- **Load fonts from Google Fonts or a CDN.** Breaks air-gapped installations and needs a CSP change
  to trust another origin.
- **System font stack.** No download at all, but every machine renders a different face with
  different widths, which is the defect being fixed.
- **Green for healthy.** It makes a healthy page loud and spends colour on what needs no action.
