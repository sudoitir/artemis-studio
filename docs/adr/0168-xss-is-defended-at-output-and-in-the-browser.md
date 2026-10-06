# ADR-0168: XSS is defended at output and in the browser, in layers

- **Status**: accepted
- **Date**: 2026-10-06
- **Deciders**: Mahdi Amirabdollahi
- **Supersedes**: [ADR-0122](0122-studio-sends-a-content-security-policy.md)

## Context

[ADR-0122](0122-studio-sends-a-content-security-policy.md) gave every response a
Content-Security-Policy that keeps scripts, connections and workers on Studio's origin. That stops an
injected script from loading or talking to another origin. It does not stop one that is already inline
in a string Studio writes into the page, and it does nothing about the other response headers.

Studio stores and shows broker data verbatim: message bodies and headers, queue and address names,
configuration. An attacker who can publish a message or name a queue controls those bytes. The
interface renders them through React, which escapes text, and through a code highlighter, which is
the one place Studio sets raw HTML (Mantine's `CodeHighlight` uses `dangerouslySetInnerHTML`).

Rewriting input is not an option. A filter that strips or escapes request bodies would corrupt the
data an operator came to look at (a message body that is HTML is a legitimate message), and it does
not defend reliably, because the dangerous form depends on where a value is later used.

## Decision

Studio does not touch input. It defends where data leaves it, and in the browser, in layers.

1. **The Content-Security-Policy** of ADR-0122 gains three directives:
   - `script-src-attr 'none'`: an inline event-handler attribute never runs, even in markup that got in.
   - `require-trusted-types-for 'script'`: the DOM's script sinks (`innerHTML`, `outerHTML`,
     `insertAdjacentHTML`, `document.write`, a script's `src`, `new Worker(url)`, `eval`) take only
     Trusted Types values, never a plain string.
   - `trusted-types default dompurify studio#worker`: the policies that may exist, and no others.

   The whole policy is:

   ```
   default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; script-src-attr 'none';
   style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:;
   connect-src 'self'; worker-src 'self' blob:; object-src 'none'; base-uri 'self';
   form-action 'self'; frame-ancestors 'none'; require-trusted-types-for 'script';
   trusted-types default dompurify studio#worker
   ```

2. **Three Trusted Types policies**, each made in `web/src/ui/trustedTypes.ts`:
   - `default` takes a plain string for `createHTML` only when it holds no `<`, so it cannot open a tag.
     It exists because Mantine writes its CSS into `<style>` elements through `innerHTML`
     (`MantineCssVariables`, `InlineStyles`, and the colour-scheme switch), a sink that has no other
     route. It does not define `createScript` or `createScriptURL`, so every script and script URL is
     refused. It is not a pass-through: any markup throws.
   - `dompurify` is DOMPurify's own policy. The highlighter's markup goes through
     `DOMPurify.sanitize` with only `<span class style>` allowed before it reaches the page, and arrives
     as `TrustedHTML`. The highlighter escapes its input already; this is the second line, and it is the
     only HTML Studio renders.
   - `studio#worker` makes the script URL of the layout worker (ELK), and throws for any URL that is not
     on Studio's origin.

3. **Other response headers**, on every response (the shell, static assets, API answers and refusals)
   through Spring Security's headers configuration: `Referrer-Policy: no-referrer`, a `Permissions-Policy`
   that turns off camera, microphone, geolocation, payment, USB and the other sensors,
   `Cross-Origin-Opener-Policy: same-origin`, `Cross-Origin-Resource-Policy: same-origin`, and the
   existing `X-Content-Type-Options: nosniff` and `X-Frame-Options: DENY`. HSTS stays Spring's default:
   sent over HTTPS only, never on plain HTTP. A plugin asset's own `Content-Security-Policy: sandbox`
   still wins for its SVGs.

4. **Untrusted bytes are never a page.** Every response that is not JSON is a download with a
   non-HTML type: the support bundle (`application/zip`, attachment), the exported `broker.xml`
   (`application/xml`, now an attachment too), and plugin assets (content type by extension, `nosniff`,
   sandboxed SVG). The SQL result, message body and recovery-code files are made in the browser from a
   `Blob` and saved with the `download` attribute. Errors, including those that escape MVC, are
   `application/problem+json` and never echo the request (`ApiErrorController`, a test).

5. **The frontend lint** (`web/eslint.config.js`) bans `dangerouslySetInnerHTML`, assignment to
   `innerHTML` and `outerHTML`, `insertAdjacentHTML`, `document.write`, `eval`, `new Function`, a
   string passed to `setTimeout` or `setInterval`, and `javascript:` addresses. A test fixes that each
   rule fires. Test files may carry an attack payload (`no-script-url` off), and nothing else is exempt.

6. **Links built from data go through `safeHref`** (`web/src/ui/safeHref.ts`): `http:`, `https:`,
   `mailto:` and addresses on Studio's own origin. It guards a plugin's vendor link and icon, an
   identity provider's start paths, and the bug-report link.

7. **The browser tests run under the policy.** The `browser` project is served with the Trusted Types
   directives, so a sink that takes a string fails a test as it would fail for a viewer. A test holds
   them equal to the server's.

## Consequences

- An injection bug that gets markup into a string Studio writes into the page throws instead of
  running, and an inline handler in markup that does get in never fires. Both the server and the browser
  are told, so neither is the only defence.
- `style-src 'unsafe-inline'` stays. Mantine sets inline `style` attributes and `<style>` elements at
  run time, and style injection runs no script. A nonce would have to reach every render. Revisit when
  Mantine can take its styles without them.
- **A plugin UI that sets raw HTML stops working.** A bundled rich-text editor, a Markdown renderer or
  any code path that writes a markup string to `innerHTML` throws under the policy, and a plugin cannot
  make its own policy: only the three names above exist. Its author renders through React or shows the
  text. Plugins built with React, Mantine and the SDK need no change. This is called out in the plugin
  guide and the template.
- `default` is a standing exception: any code on the page may still write a string without `<` to a
  markup sink. That string cannot create an element or run script, which is why it is allowed, but it
  is a rule to keep: never widen it to take a `<`.
- DOMPurify is a new dependency, used in one function.
- Violations are not reported. A reporting endpoint (`report-uri`, `POST /api/v1/csp-reports`) would
  only log, and nothing reads that log. The browser tests, the UI sweep and the plugin template's
  end-to-end run all fail on a violation, which finds it before a viewer does.
- A future Studio screen that needs a new sink needs a new named policy in the header and in
  `trustedTypes.ts`, with a reason, in a new ADR.

## Alternatives considered

- **A request filter that strips or escapes input.** Rejected for the reasons above: it corrupts
  stored data and does not defend reliably.
- **A `default` policy that returns its input.** It would make `require-trusted-types-for` decorative:
  every string would pass. The no-`<` rule keeps Mantine working and refuses the rest.
- **Replacing Mantine's style injection** (`withCssVariables={false}`, constructable style sheets).
  `InlineStyles` and the colour-scheme switch use the sink inside Mantine's components, so there is
  nothing to configure, and forking it costs more than a one-rule default policy.
- **Trusted Types in report-only first.** The browser tests already run enforced, and a report-only
  header would send nothing anywhere.
- **Sanitising on the server before sending.** The highlighter's markup is made in the browser, and
  server-side sanitising of data the UI renders as text would change the data.
- **Allow-listing a `style-src` hash or nonce** instead of `'unsafe-inline'`. See the consequence above.
