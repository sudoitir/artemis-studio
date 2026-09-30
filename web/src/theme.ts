import { createTheme, type MantineColorsTuple } from '@mantine/core';

// ─────────────────────────────────────────────────────────────────────────────
// Three-layer tokens, dark-first.
//
//   primitive  → Mantine's built-in scales + the `pine` tuple below
//   semantic   → CSS custom properties in theme.css (--as-surface, --as-danger…)
//   component  → each component reads semantic vars, never a raw primitive
//
// Keep raw colour values out of components. If you need a new colour, add it
// here or as a semantic var — not inline.
// ─────────────────────────────────────────────────────────────────────────────

const pine: MantineColorsTuple = [
  '#e6f4ef',
  '#c6e6da',
  '#9fd4c1',
  '#72c1a6',
  '#4bb18f',
  '#2fa37f',
  '#1f8f6d',
  '#137a5b',
  '#0a6249',
  '#004a37',
];

export const theme = createTheme({
  primaryColor: 'pine',
  // pine-6 is 4.03:1 with white text and on a white body, under the AA floor of 4.5:1 for a button label and a
  // link; pine-7 is 5.3:1. The dark scheme keeps pine-5 (its links need the brighter step) and theme.css darkens the
  // label of a filled button there instead.
  primaryShade: { light: 7, dark: 5 },
  colors: { pine },
  defaultRadius: 'md',
  // Every Mantine transition — the console's disclosure included — is disabled when
  // the operating system says to reduce motion. Doing it here rather than per
  // component means a new component honours it by default rather than by review.
  respectReducedMotion: true,
  fontFamily: 'Inter, ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif',
  fontFamilyMonospace: 'ui-monospace, "JetBrains Mono", "SF Mono", Menlo, Consolas, monospace',
  headings: {
    fontWeight: '600',
  },
});
