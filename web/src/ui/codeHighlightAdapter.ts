import { createShikiAdapter, type CodeHighlightAdapter } from '@mantine/code-highlight';

import { highlightedMarkup } from './trustedTypes.ts';

/**
 * Shiki, loaded by dynamic `import()` so nothing but the adapter itself is in the
 * entry chunk — the app must not pay for a highlighter before anyone opens a code
 * block. Without this provider every `<CodeHighlight language="…">` in the app
 * renders as plain text; no adapter was mounted at all before this.
 *
 * The fine-grained `shiki/core` bundle, not `shiki`'s full one: the full bundle
 * registers every grammar shiki ships as its own lazy chunk (311 files in the dist
 * for five languages we actually use).
 *
 * The theme is Shiki's CSS-variables one, so a token's colour is a semantic `--as-code-*` token
 * chosen per colour scheme in `theme.css` and measured there (non-negotiable 6). Mantine's own
 * highlighter themes are fixed hex values that miss the AA floor in both schemes, and the adapter
 * would apply them per scheme unless one theme is forced.
 */
const CODE_THEME = 'studio';

async function loadShiki() {
  const [{ createHighlighterCore, createCssVariablesTheme }, { createOnigurumaEngine }] = await Promise.all([
    import('shiki/core'),
    import('shiki/engine/oniguruma'),
  ]);
  return createHighlighterCore({
    langs: [
      import('@shikijs/langs/json'),
      import('@shikijs/langs/xml'),
      import('@shikijs/langs/yaml'),
      import('@shikijs/langs/sql'),
      import('@shikijs/langs/properties'),
    ],
    themes: [createCssVariablesTheme({ name: CODE_THEME })],
    engine: createOnigurumaEngine(import('shiki/wasm')),
  });
}

const shiki = createShikiAdapter(loadShiki, { forceColorScheme: CODE_THEME });

/**
 * The highlighter's markup is the one HTML Studio renders, so it is cleaned on the way to the page
 * rather than trusted (`highlightedMarkup`, ADR-0168).
 */
export const shikiAdapter: CodeHighlightAdapter = {
  ...shiki,
  getHighlighter: (ctx) => {
    const highlight = shiki.getHighlighter(ctx);
    return (args) => {
      const result = highlight(args);
      return result.isHighlighted ? { ...result, highlightedCode: highlightedMarkup(result.highlightedCode) } : result;
    };
  },
};
