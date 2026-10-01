# ADR-0159: The colour scheme follows the system

- **Status**: accepted
- **Date**: 2026-10-01
- **Deciders**: Mahdi Amirabdollahi

## Context

ADR-0005 set the console "dark-mode-first". `index.html` forced the dark scheme, a first visit
always opened dark, and the header control toggled between dark and light. An operator whose
operating system is light, or switches with the time of day, got a dark console regardless and had
to override it on every browser.

A remembered choice has to be applied before the first paint, or a reload shows one frame in the
wrong scheme. Mantine's answer, `ColorSchemeScript`, is an inline script, and Studio's
Content-Security-Policy allows scripts only from its own origin (`script-src 'self'`, ADR-0122). The
table density chosen by the viewer (ADR-0162) has the same first-paint problem.

## Decision

We will follow the operating system by default, with three states the viewer chooses from.

- **Three states: system, light and dark.** `MantineProvider` uses `defaultColorScheme="auto"`, and
  `index.html` no longer forces a scheme. A first visit follows the system.
- **One control, one action.** The header control cycles system, light, dark. Its accessible name
  states the scheme it switches to and, while it follows the system, the scheme the system is using
  ("Use light theme (system is dark)"). The command palette offers the same action through the same
  hook.
- **`web/public/boot-prefs.js` applies the choice before React runs.** It is a static, same-origin
  script loaded synchronously from `index.html`, so the CSP allows it without `unsafe-inline` or a
  nonce. It:
  - reads Mantine's `mantine-color-scheme-value`, accepts only `light`, `dark` or `auto`, resolves
    `auto` through `matchMedia`, and sets `data-mantine-color-scheme`;
  - reads `as:density`, accepts only its known values, and sets `data-density`;
  - ignores anything else in storage.
- **A backend test asserts that `/boot-prefs.js` is served as JavaScript.** Without it, the SPA
  fallback would quietly serve `index.html` at that path and the script would never run.
- Graph canvases take their `colorMode` from the computed scheme.

This amends ADR-0005's "dark-mode-first". Both schemes are first-class and meet the same contrast
test (ADR-0158).

## Consequences

- The console opens in the scheme the operator's system uses, and a chosen scheme opens with no
  frame of the other one.
- The CSP is unchanged.
- One more static file must stay in the build, and its whitelist must grow with any new preference
  it applies.
- `boot-prefs.js` copies Mantine's storage key and value set. A Mantine change to either breaks it
  silently, so the colour-scheme tests read the value through the script and through Mantine.
- Every screen is now designed and checked in both schemes, which doubles the screenshot sweep.

## Alternatives considered

- **Mantine's `ColorSchemeScript`.** An inline script the CSP blocks. Allowing it needs a hash that
  changes with every Mantine release, or `unsafe-inline` for scripts.
- **Apply the scheme in React after mount.** Simple, but every reload shows a frame in the wrong
  scheme.
- **A cookie read by the server, which renders the attribute.** The server would have to template
  `index.html`, which it serves as a static file today, for a preference that is the browser's own.
- **Keep dark-first.** It ignores a setting the operator has already made in their system.
