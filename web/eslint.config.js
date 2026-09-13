import js from '@eslint/js';
import globals from 'globals';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import tseslint from 'typescript-eslint';
import boundaries from 'eslint-plugin-boundaries';

// Module boundaries (ADR-0074). `warn` while the tree moves into kernel/ui/
// features/app; switched to `error` once every folder lives in its element.
const featureEdges = {
  rr: ['queues', 'clusters'],
  sql: ['messages', 'clusters'],
};

export default tseslint.config(
  { ignores: ['dist', 'node_modules'] },
  {
    files: ['src/**/*.{ts,tsx}'],
    plugins: { boundaries },
    settings: {
      'boundaries/elements': [
        { type: 'kernel', pattern: 'src/kernel' },
        { type: 'ui', pattern: 'src/ui' },
        { type: 'app', pattern: 'src/app' },
        { type: 'test', pattern: 'src/test' },
        { type: 'feature', pattern: 'src/features/*', capture: ['id'] },
      ],
      'boundaries/files': [
        { pattern: 'src/features/*/index.ts', category: 'entry' },
        { pattern: 'src/{kernel,ui,features}/**/*.test.{ts,tsx}', category: 'test-file' },
      ],
    },
    rules: {
      'boundaries/dependencies': [
        'warn',
        {
          default: 'disallow',
          policies: [
            // A test beside its code uses the shared harness; every other edge it takes is
            // held to its element's own policy.
            { from: { file: { categories: 'test-file' } }, allow: { to: { element: { type: 'test' } } } },
            { from: { element: { type: 'ui' } }, allow: { to: { element: { type: 'ui' } } } },
            { from: { element: { type: 'kernel' } }, allow: { to: { element: { type: ['kernel', 'ui'] } } } },
            { from: { element: { type: ['app', 'test'] } }, allow: { to: { element: { type: '*' } } } },
            {
              from: { element: { type: 'feature' } },
              allow: {
                to: [
                  { element: { type: ['kernel', 'ui'] } },
                  { element: { type: 'feature', captured: { id: '{{ from.element.captured.id }}' } } },
                  { element: { type: 'feature', captured: { id: 'clusters' } }, file: { categories: 'entry' } },
                ],
              },
            },
            ...Object.entries(featureEdges).map(([from, to]) => ({
              from: { element: { type: 'feature', captured: { id: from } } },
              allow: { to: { element: { type: 'feature', captured: { id: to } }, file: { categories: 'entry' } } },
            })),
          ],
        },
      ],
    },
  },
  {
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2022,
      globals: globals.browser,
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': [
        'warn',
        { allowConstantExport: true },
      ],
    },
  },
  {
    // Test files and test helpers legitimately export non-components and use
    // Node globals (MSW server lifecycle, jsdom shims).
    files: ['src/test/**', '**/*.test.{ts,tsx}'],
    languageOptions: { globals: { ...globals.node } },
    rules: { 'react-refresh/only-export-components': 'off' },
  },
);
