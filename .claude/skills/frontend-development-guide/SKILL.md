---
name: frontend-development-guide
description: High-end frontend engineering reference — architecture, code and types, design systems, UI/UX craft, user journeys, performance, accessibility, i18n/RTL, data and state, testing, and security. Load before any frontend work per .claude/rules/frontend.md.
---

# Frontend Development Guide

Universal high-end frontend practice, applied to Mentora's actual surfaces
(`web/back-office` — React 18 + Refine + Ant Design; `web/landing` — Astro 5;
`clients/apple/Packages/MentoraUI` — SwiftUI). Read this before touching UI. The binding
non-negotiables live in `.claude/rules/frontend.md` — this is the reasoning behind them and the
depth a short rule can't carry.

## 0. Vision & development approach

"High-end" is coherence and restraint, not effects. A screen with one well-considered
typographic scale, one spacing rhythm, and no gratuitous motion reads as premium; a screen with
three accent colors and a hero animation does not. The design system (tokens → components →
patterns) is infrastructure, the same category as the database schema — it is not a style sheet
applied at the end.

Deliver in layers: one real screen, end to end — real data, every state (loading/empty/error),
RTL, keyboard, mobile — before starting the next. A grid of half-built screens is not a
milestone; it is unfinished work wearing a demo's clothes.

Sequence a surface in this order: **journey → states → tokens → markup → interaction →
polish.** Skipping to markup before the states are defined is how "just add a spinner" becomes
a redesign three weeks later.

What a principal engineer refuses to ship: a mutation with no optimistic/error path, a form
that can be double-submitted, a table with no empty state, a screen that only works at the
designer's viewport width, an animation that exists because the library made it easy. When
schedule pressure argues for shipping one of these anyway, the counter is concrete: name the
support ticket or the re-work cost it creates, not "it doesn't feel right."

## 1. Architecture

- **Feature-sliced, not type-sliced.** Group by feature/domain (`roadmap/`, `catalog/`), not by
  artifact type (`components/`, `hooks/`, `utils/` at the top level). A feature folder holds its
  own components, hooks, and types; only what's genuinely cross-feature lives in `shared/`.
- **The dependency rule**: features never import from other features directly. Cross-feature
  needs go through `shared/` or an explicit composition point (a page/route that imports both).
  This is what keeps a codebase deletable — you can remove a feature folder and nothing else
  breaks.
- **Composition over configuration.** Prefer components that compose (`<Card><Card.Header/>`)
  over one component with fifteen boolean props. A boolean-prop explosion is a sign the
  component is doing several jobs.
- **Server/client boundary discipline.** Know, for every file, whether it runs on the server,
  the client, or both — and don't let a client-only concern (browser APIs, event handlers) leak
  into code that's meant to be isomorphic.
- **When a component is too big**: if you can't describe what it does in one sentence without
  "and", it's two components. If a file exceeds ~200-300 lines, look for the seam.

## 2. Code & types

- No `any`. No `as` casts to silence the compiler — fix the type or narrow with a guard.
- **Parse, don't validate**, at the network edge: turn the untyped API response into a typed,
  validated value once, at the boundary; everything downstream trusts the type. This is what
  generated OpenAPI types + a thin runtime check buys you.
- **Discriminated unions for state**, not a pile of booleans (`isLoading && !isError && data`).
  `{status: 'idle'|'loading'|'error'|'success', ...}` makes impossible states unrepresentable.
- Exhaustive `switch` over a discriminated union, with a `never` default so a new variant is a
  compile error, not a silent fallthrough.
- **Error boundaries per route**, not one global boundary — a crash in one panel shouldn't blank
  the whole app.
- `useEffect` is for synchronizing with an external system, not for computing derived state.
  If a value can be computed from props/state during render, compute it during render.
- Every async effect that can outlive its component: cleanup and `AbortController`. Every list:
  a stable key that isn't the array index once the list can reorder.
- Memoize only with a measured reason (profiler shows a real re-render cost) — premature
  `useMemo`/`useCallback` is noise that hides the real bottleneck when one shows up.

## 3. Design system

- **Three-layer tokens**: primitive (raw values) → semantic (`color-content-primary`,
  `space-section-gap`) → component (`button-padding-inline`). Components consume semantic or
  component tokens, never primitives directly — that's what lets a rebrand change one file.
- **Component API design**: support controlled and uncontrolled where the pattern calls for it;
  use slot/composition patterns (`asChild`-style polymorphism) over a `variant` prop explosion
  when the shapes genuinely differ.
- **Wrap a vendor component only when you need to**: constrain its API to the token system, fix
  an a11y gap it has, or centralize a behavior used in many places. Otherwise use it directly —
  a wrapper that just forwards props is a maintenance tax with no payoff.
- **Theming as token swaps**, not conditional styling scattered through components. Dark mode
  (or Mentora's single dark "stage" theme on landing) is a different set of token values, not a
  parallel set of components.
- **Density and grid**: an 8pt (or 4pt for dense UI) spacing scale, applied consistently — no
  one-off `13px` margins.
- **Type scale**: a small, deliberate set of sizes; line length 45–75 characters for body text;
  vertical rhythm derived from the line-height, not eyeballed per element.

## 4. UI/UX craft

- **Hierarchy**: squint at the screen — does the important thing still win? If everything is
  bold, nothing is.
- **Spacing is a relationship**, not decoration: related things sit closer together than
  unrelated things (proximity > borders/dividers for grouping).
- **The four-second rule**: a user should identify a screen's purpose and primary action within
  four seconds. If it takes a legend, it's not designed yet.
- Contrast and elevation used **semantically** — a raised surface means "this is interactive or
  layered," not "I liked the shadow."
- **Motion as feedback**: 120–240ms, standard easing, and every animation answers "what is this
  telling the user?" No animation without a job — entrance animations on every card on every
  load is noise, not delight. Always respect `prefers-reduced-motion`.
- **Empty states teach**: not "No items" — what an item is, why there are none, and the action
  that creates one.
- **Error copy** states the cause and the next action ("Course generation failed — the model
  provider timed out. Retry?"), never a bare "Something went wrong."
- **Forms**: labels always visible (not placeholder-as-label), inline validation on blur (not
  only on submit), single column for anything non-trivial, never disable submit silently —
  either show why it's disabled or leave it enabled and validate on click.
- **Tables**: sticky header on scroll, column widths that don't force horizontal scroll on a
  laptop, a real empty state, and pagination or virtualization once rows exceed a few hundred.
- **Anti-slop list** — defaults that read as AI-templated, avoid unless deliberately chosen:
  generic purple/blue gradient heroes, indiscriminate glassmorphism, the same three-column
  feature-icon-grid, emoji as icons, Inter/system-ui with no pairing thought, drop shadows on
  everything, centered-everything layouts with no asymmetry.

## 5. User journey

- **Map the journey before the screen**: entry point → first meaningful action → likely friction
  → success → recovery from failure. Design the screen to serve that map, not the other way
  around.
- **Onboarding as progressive disclosure** — teach one concept per screen, in context, not a
  five-slide tour before the product is touched.
- **Time-to-first-value** is the metric that matters more than screen count: how fast does a new
  user reach something that proves the product's worth.
- **Auth as a state machine**: signed-out → signing-in → signed-in → token-expiring →
  refreshing → signed-out-on-failure. Every transition needs a UI state, especially silent
  token refresh failing mid-session.
- **Optimistic UI**: apply it where the failure rate is low and the rollback is cheap and
  visible (toggle a like); don't apply it to a multi-step, hard-to-reverse mutation (a paid
  transfer) — show pending state honestly there instead.
- **Offline and reconnect**: define what happens to in-flight actions when the network drops,
  and what the user sees on reconnect — never a silent data loss.
- **Destructive actions get a confirmation that names the consequence** ("Delete 12 courses?
  This cannot be undone"), not a generic "Are you sure?".
- **Every mutation defines where the user lands** — same screen with updated state, a new
  screen, a toast — decided deliberately, not by default framework behavior.
- **Instrumentation**: name the events that must fire to prove the journey works end to end
  (started, succeeded, abandoned-at-step-N) before calling the journey done.

## 6. Performance

- **Budgets** (mid-tier device, throttled 4G): LCP ≤ 2.5s, INP ≤ 200ms, CLS ≤ 0.1, TTFB governed
  by the backend SLA, JS transfer sized to the route's actual need.
- **Critical path**: identify what must load before first paint vs. what can stream in after.
- **Code-split by route first, then by interaction** (a modal's contents, a rarely-used panel).
- **Images**: explicit width/height always (prevents CLS), `srcset`/responsive sizes, modern
  formats (AVIF/WebP with fallback), lazy-load anything below the fold.
- **Fonts**: `font-display: swap` (or `optional` where layout shift matters more than FOIT),
  subset to the scripts used, preload the one weight the LCP element needs, Vazirmatn for
  Persian text loaded with the same discipline as the Latin face.
- **List virtualization** once a list can exceed a few hundred DOM nodes.
- **Avoid request waterfalls** — parallelize independent fetches, colocate data needs with the
  route that needs them so the framework can hoist them.
- **Caching/revalidation**: define staleness tolerance per data type; don't refetch on every
  focus event by default.
- **Measure on a throttled mid-tier device or CI Lighthouse run — never trust the dev machine's
  numbers.**

## 7. Accessibility

- WCAG 2.2 AA is the floor, not the target to negotiate down from.
- **Semantic HTML first** — a `<button>` before a `<div onClick>`, real `<table>` markup before
  a styled grid of divs. ARIA is a patch for what semantic HTML can't express, not a starting
  point.
- **Focus management**: move focus deliberately on route change and when a dialog opens; return
  it to the trigger on close; trap focus inside an open modal without breaking `Escape`.
- **Skip links** for keyboard users to bypass repeated navigation.
- **Live regions** (`aria-live`) for content that changes without a page navigation — async
  results, toasts, form-submission outcomes.
- **Naming priority**: visible `<label>` > `aria-label` > nothing. Every interactive element
  needs an accessible name a screen reader can announce.
- **Target size** ≥ 24×24px (WCAG 2.2 minimum) for anything tappable.
- **Color is never the sole carrier of meaning** — pair it with an icon, text, or pattern
  (error state = red **and** an icon **and** error text, not red alone).
- A keyboard-only pass and a screen-reader pass are part of reviewing any new screen, not a
  separate audit done later.

## 8. i18n & RTL

- Persian is the primary language and direction here, not a translated afterthought — design
  and build RTL-first, verify LTR (English) as the secondary case.
- **Logical properties only**: `margin-inline-start`, `padding-block-end`, `inset-inline-end`,
  `text-align: start`. Physical properties (`left`, `right`, `margin-left`) hardcode a
  direction and break under `dir="rtl"`.
- **Bidi isolation** for mixed content — a Latin username or a URL inside a Persian sentence
  needs `dir="auto"` or `<bdi>`/Unicode isolation marks so punctuation doesn't reorder.
- **Numbers and dates**: use `Intl.NumberFormat`/`Intl.DateTimeFormat` with the correct locale,
  not string concatenation; be explicit about Persian (Jalali) vs. Gregorian calendar per
  context — don't assume one silently.
- **Text expansion headroom**: Persian and its UI chrome can run longer than the English source;
  don't hardcode widths that clip at the design-language's default length.
- **Icon mirroring**: directional icons (back/forward arrows, chevrons showing progression)
  mirror in RTL; icons representing a real-world object (a clock, a play button) do not.

## 9. Data & state

- **Four kinds of state, four owners**: server state (data-provider/query cache), URL state
  (filters, selected tab, pagination — anything the user should be able to bookmark/share),
  form state (local to the form until submit), ephemeral UI state (hover, open/closed — plain
  local state). Putting server state in a global store, or filter state outside the URL, is the
  most common state bug source.
- **Cache keys and invalidation**: key by the exact parameters that make a query unique;
  invalidate precisely on the mutation that changes that data, not with a blanket refetch-all.
- **Pagination vs. infinite scroll**: pagination for anything the user needs to reference a
  specific position in (admin tables); infinite scroll for consumption feeds — pick by the
  journey, not by default.
- **Races**: any request that can be superseded by a newer one (search-as-you-type, tab
  switches) needs an `AbortController` or a "is this still the latest request" guard.
- **Retry/backoff**: retry transient network failures with backoff; never retry a mutation that
  isn't idempotent without an idempotency key.
- **Auth tokens in the browser**: not `localStorage` (XSS-readable) — httpOnly cookies or an
  in-memory token with refresh, matching `verify-browser-boundaries.mjs`'s ban on app-level
  `localStorage`.
- **Streaming/long-running jobs** (course generation, roadmap generation): design the progress
  UX explicitly — percentage or step indicator, what happens on reconnect mid-job, how a
  timeout is distinguished from a failure in the UI.

## 10. Testing

- Pyramid at frontend altitude: unit tests for logic/hooks, component tests for behavior,
  a handful of Playwright journeys for the paths that must never break, no more.
- **Testing Library queries by role and accessible name** (`getByRole('button', {name: /save/i})`),
  not by class name or a `data-testid` used as a crutch for markup that has no real semantics.
  If you need a `data-testid`, that's often a sign the element should have a proper role/label.
- **MSW** mocks the network boundary — test the component against realistic API responses, not
  against a hand-rolled fetch stub.
- **Playwright** covers the journey (multi-step, real browser, real focus/keyboard behavior);
  unit/component tests cover the logic. Don't duplicate one pyramid layer's job in another.
- Visual regression only where the payoff is real (a shared component library, a landing page
  hero) — not blanket screenshot diffing of every screen, which just generates noise.
- A11y assertions belong in CI (automated axe-core checks) as a floor; they don't replace a
  manual keyboard/screen-reader pass.
- Test RTL layouts explicitly — a component tested only in LTR can silently break under
  `dir="rtl"`.
- Flake discipline: a flaky test gets fixed or deleted, not retried into green.

## 11. Security

- **XSS**: `dangerouslySetInnerHTML` (or any raw-HTML injection) requires a sanitizer
  (e.g. DOMPurify) and a comment explaining why raw HTML is necessary at all.
- **CSP**: know what the deployed CSP allows before adding an inline script, a new script host,
  or an eval-requiring library.
- **Never trust client-side authorization** — hiding a button is UX, not security; the backend
  enforces the actual permission check.
- **Secrets never ship in the bundle** — anything importable by the browser is public; verify
  before adding an env var to a client-side config.
- **Dependency hygiene**: prefer maintained, widely-used libraries (per the global engineering
  principles); check for known CVEs on anything handling user input or auth.
- **`target="_blank"`** always pairs with `rel="noopener noreferrer"` to prevent tabnabbing.

## 12. Review checklist

Before calling a UI change done:

- [ ] Opened in a real browser at 360px, 768px, 1280px, 1920px
- [ ] Keyboard-only pass: every interaction reachable and operable, focus visible throughout
- [ ] Loading / empty / error / partial / offline states all implemented, not just happy path
- [ ] RTL verified (not just mirrored — read the Persian copy in context)
- [ ] No raw color/spacing literals; tokens only
- [ ] No physical CSS properties (`left`/`right`/`margin-left`) anywhere touched
- [ ] Types come from generated contracts, no hand-typed DTOs
- [ ] No `localStorage`/`console.*`/raw absolute-URL `fetch` in app code
- [ ] Destructive actions confirm with a concrete consequence
- [ ] Performance budget checked, not assumed
- [ ] Tests query by role/name; journey covered by Playwright if it's a new flow
