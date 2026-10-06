import {
  createTheme,
  Drawer,
  Modal,
  Notification,
  type CSSVariablesResolver,
  type MantineColorsTuple,
} from '@mantine/core';

// ─────────────────────────────────────────────────────────────────────────────
// Three-layer tokens (ADR-0157).
//
//   primitive  → this file: colour tuples, the type scale, spacing, radius, shadows and `other`
//   semantic   → CSS custom properties in theme.css (--as-surface, --as-danger…)
//   component  → each component reads semantic vars, never a raw primitive
//
// Keep raw colour values out of components. If you need a new colour, add it here or as a
// semantic var in theme.css, not inline. Components that need a number in JavaScript read it
// from `useMantineTheme().other`, never by importing this file.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Graphite: near-monochrome surfaces, borders and text (ADR-0158). It feeds Mantine's `gray`
 * (light scheme) and, as `dark`, the dark scheme. Steps run lightest to darkest, so Mantine's
 * conventions hold in both: gray-0 is the page, gray-4 a control's border, gray-9 the text;
 * dark-0 is the text, dark-4 a control's border, dark-6 a raised surface, dark-7 the body.
 */
const graphite: MantineColorsTuple = [
  '#F7F8FA',
  '#EEF0F4',
  '#E1E4EA',
  '#CBD0D9',
  '#7F8897', // 3.4:1 on the page, 3.6:1 on white: a control's border
  '#6B7484',
  '#5A6271', // dimmed text, 5.8:1 on the page
  '#3F4755',
  '#2A313D',
  '#1A1F27', // text
];

const graphiteDark: MantineColorsTuple = [
  '#E8EAF0', // text
  '#CFD4DD',
  '#A6AEBB', // dimmed text
  '#808998',
  '#6B7585', // a control's border, 3.5:1 on a raised surface
  '#2A313D', // hover, and the border between surfaces
  '#1B2028', // raised surface
  '#14181E', // body
  '#0F1217',
  '#0A0C10',
];

/**
 * Cobalt: the one accent. Steps 6 and 7 are the fill, resting and hovered, in both schemes (primaryShade
 * below). A fill has to hold two floors at once: 3:1 against the darkest surfaces it sits on (WCAG 1.4.11,
 * 3.5:1 on the raised dark surface) and 4.5:1 for its white label. That leaves a band of luminance
 * between 0.143 and 0.179, and both steps are inside it. Step 6 is therefore too light to read as link
 * text on a light surface; the light scheme's anchor takes step 8.
 */
const cobalt: MantineColorsTuple = [
  '#EEF2FF',
  '#DCE5FF',
  '#C2D0FF',
  '#A5B9FF',
  '#86A2FF',
  '#5A7DEC',
  '#496CDF',
  '#3E64DD',
  '#1B3896',
  '#142A73',
];

/**
 * Amber: degraded, lagging, at risk. Warning and danger are told apart by lightness as well as hue: amber
 * is the lighter of the two (4 on dark, 6 on light), signal the darker (see theme.css).
 */
const amber: MantineColorsTuple = [
  '#FFF7E8',
  '#FFEBC7',
  '#FFDDA0',
  '#FACB7C',
  '#F5C37C',
  '#C06A0C',
  '#9D5009',
  '#7E3B00',
  '#632E00',
  '#4A2200',
];

/** Signal: failed, down, destructive. */
const signal: MantineColorsTuple = [
  '#FFF1F0',
  '#FFDEDB',
  '#FFC5BF',
  '#FFA79F',
  '#F18279',
  '#E0574B',
  '#B42318',
  '#911A11',
  '#701410',
  '#520E0B',
];

const other = {
  density: {
    compact: { rowH: 28, pad: 8 },
    comfortable: { rowH: 36, pad: 12 },
  },
  motion: { fast: 120, base: 180, slow: 240 },
  z: { sticky: 2, header: 10, nav: 20 },
  layout: { navW: 264, navRailW: 64 },
};

const FONT_FALLBACK = 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif';
const MONO_FALLBACK = 'ui-monospace, "SF Mono", Menlo, Consolas, monospace';

const dialogTitle = {
  fontSize: 'var(--mantine-h3-font-size)',
  fontWeight: 'var(--mantine-h3-font-weight)',
  lineHeight: 'var(--mantine-h3-line-height)',
} as const;

export const theme = createTheme({
  primaryColor: 'cobalt',
  // One fill shade in both schemes: Mantine resolves `autoContrast` once, from the light shade, into an
  // inline label colour, so a fill that changed shade with the scheme would keep the other scheme's label.
  // Cobalt-6 carries a white label at 4.7:1 and stands at 3.5:1 against the raised dark surface (3.8:1
  // against the page); its hover, cobalt-7, keeps 3.2:1 (theme.contrast.test.ts measures both).
  primaryShade: 6,
  // The label of every filled control (Button, ActionIcon, Badge, Avatar) is white or black, whichever
  // measures higher. 0.179 is where the two contrast ratios are equal (4.58:1); Mantine's default of 0.3
  // leaves a white label at 3:1 on a mid-tone fill.
  autoContrast: true,
  luminanceThreshold: 0.179,
  colors: {
    graphite,
    gray: graphite,
    dark: graphiteDark,
    cobalt,
    amber,
    signal,
    // Mantine's own names for the same three tones, so a control that still asks for "red" gets signal.
    blue: cobalt,
    yellow: amber,
    red: signal,
  },
  defaultRadius: 'sm',
  radius: { xs: '0.125rem', sm: '0.25rem', md: '0.375rem', lg: '0.5rem', xl: '0.75rem' },
  spacing: { xs: '0.25rem', sm: '0.5rem', md: '0.75rem', lg: '1rem', xl: '1.5rem' },
  fontSizes: { xs: '0.75rem', sm: '0.8125rem', md: '0.875rem', lg: '1rem', xl: '1.25rem' },
  lineHeights: { xs: '1.35', sm: '1.4', md: '1.45', lg: '1.5', xl: '1.4' },
  shadows: {
    xs: '0 1px 2px rgba(10, 12, 16, 0.08)',
    sm: '0 1px 3px rgba(10, 12, 16, 0.12), 0 1px 2px rgba(10, 12, 16, 0.08)',
    md: '0 4px 12px rgba(10, 12, 16, 0.16), 0 1px 3px rgba(10, 12, 16, 0.1)',
    lg: '0 12px 28px rgba(10, 12, 16, 0.2), 0 2px 6px rgba(10, 12, 16, 0.12)',
    xl: '0 20px 44px rgba(10, 12, 16, 0.28), 0 4px 10px rgba(10, 12, 16, 0.14)',
  },
  // Every Mantine transition — the console's disclosure included — is disabled when
  // the operating system says to reduce motion. Doing it here rather than per
  // component means a new component honours it by default rather than by review.
  respectReducedMotion: true,
  // The two typefaces are bundled (main.tsx); the fallback faces in theme.css carry the metrics of the
  // real ones, so the swap does not move the layout.
  fontFamily: `'Atkinson Hyperlegible Next Variable', 'Atkinson Hyperlegible Next Fallback', ${FONT_FALLBACK}`,
  fontFamilyMonospace: `'Atkinson Hyperlegible Mono Variable', 'Atkinson Hyperlegible Mono Fallback', ${MONO_FALLBACK}`,
  headings: {
    fontFamily: `'Atkinson Hyperlegible Next Variable', 'Atkinson Hyperlegible Next Fallback', ${FONT_FALLBACK}`,
    fontWeight: '600',
    sizes: {
      h1: { fontSize: '1.375rem', lineHeight: '1.3' },
      h2: { fontSize: '1.125rem', lineHeight: '1.35' },
      h3: { fontSize: '1rem', lineHeight: '1.4' },
      h4: { fontSize: '0.875rem', lineHeight: '1.4' },
      h5: { fontSize: '0.8125rem', lineHeight: '1.4' },
      h6: { fontSize: '0.75rem', lineHeight: '1.4' },
    },
  },
  components: {
    // Mantine's close button is an icon with no name; every dialog and drawer gets the same one. Its
    // title is the dialog's heading, so it reads as one: the h3 size and the heading weight.
    Modal: Modal.extend({
      defaultProps: { closeButtonProps: { 'aria-label': 'Close' } },
      styles: { title: dialogTitle },
    }),
    Drawer: Drawer.extend({
      defaultProps: { closeButtonProps: { 'aria-label': 'Close' } },
      styles: { title: dialogTitle },
    }),
    // A toast's close button is nameless the same way.
    Notification: Notification.extend({
      defaultProps: { closeButtonProps: { 'aria-label': 'Close' } },
    }),
  },
  other,
});

/**
 * The numbers CSS and JavaScript both read, emitted once so they cannot drift: a row's height is the
 * one the virtualizer uses and the one the grid draws. Passed to `MantineProvider` as a prop, not as a
 * theme field.
 *
 * The scheme blocks set Mantine's surface variables from graphite: the page, the raised surface and the
 * text, so a Mantine component and a `--as-*` token agree on what a surface is.
 */
export const cssVariablesResolver: CSSVariablesResolver = (t) => {
  const { density, motion, z, layout } = t.other as typeof other;
  return {
    variables: {
      '--as-row-h-compact': `${density.compact.rowH / 16}rem`,
      '--as-row-h-comfortable': `${density.comfortable.rowH / 16}rem`,
      '--as-cell-pad-compact': `${density.compact.pad / 16}rem`,
      '--as-cell-pad-comfortable': `${density.comfortable.pad / 16}rem`,
      '--as-duration-fast': `${motion.fast}ms`,
      '--as-duration-base': `${motion.base}ms`,
      '--as-duration-slow': `${motion.slow}ms`,
      '--as-z-sticky': `${z.sticky}`,
      '--as-z-header': `${z.header}`,
      '--as-z-nav': `${z.nav}`,
      '--as-nav-w': `${layout.navW / 16}rem`,
      '--as-nav-rail-w': `${layout.navRailW / 16}rem`,
    },
    light: {
      '--mantine-color-body': graphite[0],
      '--mantine-color-text': graphite[9],
      '--mantine-color-dimmed': graphite[6],
      '--mantine-color-default': t.white,
      '--mantine-color-default-hover': graphite[1],
      '--mantine-color-default-border': graphite[2],
      '--mantine-color-default-color': graphite[9],
      '--mantine-color-placeholder': graphite[6],
      '--mantine-color-anchor': cobalt[8],
      '--mantine-color-error': signal[6],
    },
    dark: {
      '--mantine-color-body': graphiteDark[7],
      '--mantine-color-text': graphiteDark[0],
      '--mantine-color-dimmed': graphiteDark[2],
      '--mantine-color-default': graphiteDark[6],
      '--mantine-color-default-hover': graphiteDark[5],
      '--mantine-color-default-border': graphiteDark[5],
      '--mantine-color-default-color': graphiteDark[0],
      '--mantine-color-placeholder': graphiteDark[2],
      '--mantine-color-anchor': cobalt[4],
      '--mantine-color-error': signal[4],
    },
  };
};
