import DOMPurify from 'dompurify';

/**
 * Studio sends `require-trusted-types-for 'script'` (ADR-0168): the DOM's script sinks (`innerHTML`,
 * `outerHTML`, `insertAdjacentHTML`, `document.write`, `new Worker(url)`, script `src`, `eval`) refuse
 * a plain string, so an injected string cannot become markup or code. The policy names the page may
 * create are listed in the same header, and this file is where each one is made:
 *
 * - `default`: the one place a plain string is still taken, and only text that holds no `<`, so it
 *   cannot open a tag. Mantine writes CSS into `<style>` elements through `innerHTML`, and that is
 *   all this lets through; any markup, and any script or script URL, is refused.
 * - `dompurify`: DOMPurify's own policy, for the highlighter's markup.
 * - `studio#worker`: the script URL of the layout worker, which must be on this origin.
 *
 * A fourth would be a new entry in the header and in this list, with a reason.
 */

/** The browser's Trusted Types factory, absent where the API is (tests, older browsers). */
function factory(): TrustedTypePolicyFactory | undefined {
  return (globalThis as { trustedTypes?: TrustedTypePolicyFactory }).trustedTypes;
}

/** Installed once, before anything renders. Where Trusted Types is not enforced it changes nothing. */
export function installDefaultPolicy(): void {
  factory()?.createPolicy('default', {
    createHTML: (input) => (input.includes('<') ? (null as unknown as string) : input),
  });
}

let workerPolicy: Pick<TrustedTypePolicy, 'createScriptURL'> | undefined;

/**
 * A script URL for `new Worker(...)`: refused unless it is on this page's origin. Without Trusted
 * Types (tests, older browsers) it is the string itself.
 */
export function workerScriptUrl(url: string): string {
  const trustedTypes = factory();
  if (!trustedTypes) return url;
  workerPolicy ??= trustedTypes.createPolicy('studio#worker', {
    createScriptURL: (input: string) => {
      if (new URL(input, globalThis.location.href).origin !== globalThis.location.origin) {
        throw new TypeError('A worker script must come from this origin.');
      }
      return input;
    },
  });
  // A TrustedScriptURL stringifies to its URL, and a Worker takes it as it is.
  return workerPolicy.createScriptURL(url) as unknown as string;
}

/**
 * The markup the code highlighter produced, cleaned. Syntax-highlighted code is the one place Studio
 * renders HTML (Mantine sets it with `dangerouslySetInnerHTML`), and its text comes from broker data:
 * message bodies, headers, configuration. The highlighter escapes it, and this is the second line: only
 * `<span>` elements with a class and a style survive, whatever the input, and where Trusted Types is
 * available the result is a `TrustedHTML` the sink accepts. Typed as a string because that is what
 * Mantine's adapter contract says; React hands it to the sink untouched.
 */
export function highlightedMarkup(html: string): string {
  return DOMPurify.sanitize(html, {
    ALLOWED_TAGS: ['span'],
    ALLOWED_ATTR: ['class', 'style'],
    RETURN_TRUSTED_TYPE: true,
  }) as unknown as string;
}
