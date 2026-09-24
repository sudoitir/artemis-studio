/**
 * The libraries a plugin's UI takes from Studio instead of bundling (ADR-0100): the ones holding
 * React context or module state, where a second copy breaks hooks, routing, theming or the query
 * cache. Studio's own build (web/vite.config.ts) shares exactly these, so the list lives here, once.
 */
export const SHARED_LIBRARIES = [
  'react',
  'react/jsx-runtime',
  'react-dom',
  'react-dom/client',
  '@mantine/core',
  '@mantine/hooks',
  '@tanstack/react-query',
  '@tanstack/react-router',
];

/** The SDK itself: always the running Studio's copy. */
export const SDK = '@artemis-studio/plugin-sdk';

/** The package a shared specifier belongs to: `react-dom/client` → `react-dom`. */
export function packageOf(specifier) {
  const parts = specifier.split('/');
  return specifier.startsWith('@') ? parts.slice(0, 2).join('/') : parts[0];
}
