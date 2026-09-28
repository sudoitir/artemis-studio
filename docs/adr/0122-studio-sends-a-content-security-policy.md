# ADR-0122: Studio sends a Content-Security-Policy

- **Status**: accepted
- **Date**: 2026-09-28
- **Deciders**: Mahdi Amirabdollahi

## Context

Studio relied on Spring Security's default headers: `X-Frame-Options: DENY`, `nosniff`, and HSTS
over HTTPS. It sent no Content-Security-Policy. The SPA renders broker data (message bodies,
headers, names) that an attacker may control. React escapes text, but a single injection bug
anywhere would run script with the viewer's session and could send data anywhere. Plugin UIs are
Module Federation remotes served from Studio's own origin ([ADR-0100](0100-plugin-uis-are-module-federation-remotes.md)).
Their `icon.svg` is served with `CSP: sandbox`, but other SVGs under `ui/` were not, so one opened
directly was same-origin script.

## Decision

- Every response carries:

  ```
  default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline';
  img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self';
  worker-src 'self' blob:; object-src 'none'; base-uri 'self'; form-action 'self';
  frame-ancestors 'none'
  ```

- Every SVG a plugin serves carries `Content-Security-Policy: sandbox`, as its icon already did.
- The plugin template's end-to-end run fails on any CSP refusal in the browser console.

## Consequences

- An injected script cannot load from or talk to another origin, and Studio cannot be framed.
- `'unsafe-inline'` stays for styles: Mantine injects styles at runtime, and style injection runs
  no script.
- `'wasm-unsafe-eval'` lets the code highlighter (Shiki's Oniguruma engine) compile its WebAssembly.
  It permits WebAssembly compilation only, never JavaScript `eval` or `new Function`.
- A plugin UI cannot load scripts, fonts or data from other origins. It bundles what it needs, as
  the plugin guide already asks.

## Alternatives considered

- **Nonces for styles.** Mantine and Emotion-style injection would need a nonce threaded through
  every render. The gain is small, because the script policy is what stops code execution.
- **Report-only first.** There is no reporting endpoint to collect reports, and the template e2e
  exercises the real UI, which is a better signal.
