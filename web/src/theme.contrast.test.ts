import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { DEFAULT_THEME, mergeMantineTheme } from '@mantine/core';
import { describe, expect, it } from 'vitest';

import { cssVariablesResolver, theme } from './theme.ts';

/**
 * WCAG contrast of the semantic tokens (ADR-0157): every text token on every surface token, and every
 * non-text mark on the surfaces it is drawn on, in both schemes. The values are read from the files the
 * browser reads, `theme.css` and the theme's own tuples and resolver, so the test cannot drift from them.
 */

type Rgba = readonly [number, number, number, number];
type Vars = Record<string, string>;
type Scheme = 'light' | 'dark';

const TEXT_MIN = 4.5;
const NON_TEXT_MIN = 3;

const mantine = mergeMantineTheme(DEFAULT_THEME, theme);
const css = readFileSync(resolve(process.cwd(), 'src/theme.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');

/** The declarations of every top-level rule whose selector is exactly `selector`. */
function declarations(selector: string): Vars {
  const out: Vars = {};
  for (const [, sel, body] of css.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    if (sel.trim() !== selector) continue;
    for (const decl of body.split(';')) {
      const colon = decl.indexOf(':');
      if (colon > 0) out[decl.slice(0, colon).trim()] = decl.slice(colon + 1).trim();
    }
  }
  return out;
}

/** What `MantineProvider` puts on `:root` for a scheme: the colour ramps, then the resolver's variables. */
function mantineVars(scheme: Scheme): Vars {
  const vars: Vars = { '--mantine-color-white': mantine.white, '--mantine-color-black': mantine.black };
  for (const [name, tuple] of Object.entries(mantine.colors)) {
    tuple.forEach((value, i) => (vars[`--mantine-color-${name}-${i}`] = value));
  }
  const resolved = cssVariablesResolver(mantine);
  return { ...vars, ...resolved.variables, ...resolved[scheme] };
}

function schemeVars(scheme: Scheme): Vars {
  return {
    ...mantineVars(scheme),
    ...declarations(':root'),
    ...(scheme === 'light' ? declarations(":root[data-mantine-color-scheme='light']") : {}),
  };
}

function value(vars: Vars, name: string, depth = 0): string {
  const raw = vars[name];
  if (raw === undefined || depth > 12) throw new Error(`${name} does not resolve`);
  return raw.replace(/var\((--[\w-]+)\)/g, (_, ref: string) => value(vars, ref, depth + 1));
}

function parse(color: string): Rgba {
  const hex = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(color);
  if (hex) {
    const digits = hex[1].length === 3 ? [...hex[1]].map((d) => d + d).join('') : hex[1];
    const n = parseInt(digits, 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255, 1];
  }
  const rgba = /^rgba?\(([^)]+)\)$/.exec(color);
  if (rgba) {
    const [r, g, b, a = '1'] = rgba[1].split(',').map((part) => part.trim());
    return [Number(r), Number(g), Number(b), Number(a)];
  }
  throw new Error(`cannot read the colour "${color}"`);
}

/** `top` laid over the opaque `under`. */
function over(top: Rgba, under: Rgba): Rgba {
  const a = top[3];
  return [0, 1, 2].map((i) => top[i] * a + under[i] * (1 - a)).concat(1) as unknown as Rgba;
}

function luminance([r, g, b]: Rgba): number {
  const [lr, lg, lb] = [r, g, b].map((c) => {
    const s = c / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * lr + 0.7152 * lg + 0.0722 * lb;
}

function ratio(a: Rgba, b: Rgba): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

const SURFACES = [
  '--as-surface',
  '--as-surface-raised',
  '--as-grid-header-bg',
  '--as-grid-row-hover',
  '--as-code-bg',
  '--as-canvas-sunken',
  '--as-graph-node-bg',
  '--as-flow-node-bg',
  '--as-palette-bg',
];

/** Translucent surfaces: a tint is always laid over a surface, here the two a row sits on. */
const TINTS = ['--as-selected', '--as-grid-row-fresh', '--as-code-active-line'];
const TINTED_ON = ['--as-surface', '--as-surface-raised', '--as-code-bg'];

const TEXT = [
  '--as-text',
  '--as-text-dimmed',
  '--as-table-header-text',
  '--as-link',
  '--as-ok',
  '--as-warning',
  '--as-danger',
  '--as-grid-num',
  '--as-grid-stale',
  '--as-live',
  '--as-stale',
  '--as-offline',
  '--as-node-live',
  '--as-node-backup',
  '--as-node-unmanaged',
  '--as-flow-fault',
  '--as-flow-depth',
  '--as-flow-lane',
  '--as-rr-in-flight',
  '--as-rr-resolved',
  '--as-rr-failed',
  '--as-chart-axis',
  '--mantine-color-error',
  '--mantine-color-dimmed',
];

/** Syntax colours are text too, but only the editor and the code blocks draw them: on the surfaces below. */
const CODE_TEXT = [
  '--as-code-keyword',
  '--as-code-string',
  '--as-code-number',
  '--as-code-comment',
  '--as-code-property',
  '--as-code-meta',
];
const CODE_ON = [
  '--as-surface',
  '--as-surface-raised',
  '--as-code-bg',
  '--as-grid-row-hover',
  '--as-code-active-line over --as-surface',
  '--as-code-active-line over --as-code-bg',
];

/** Marks that carry state or find the eye (focus, a control's edge, a series, an edge of the graph). */
const NON_TEXT = [
  '--as-accent',
  '--as-border-strong',
  '--as-node-split-brain',
  '--as-node-down',
  '--as-axis-broken',
  '--as-graph-edge-behind',
  '--as-alert-dot',
  '--as-flow-edge',
  '--as-flow-dot',
  '--as-chart-1',
  '--as-chart-2',
  '--as-chart-3',
  '--as-chart-4',
  '--as-chart-threshold',
  '--as-chart-seq-1',
  '--as-chart-seq-2',
  '--as-chart-seq-3',
];
const NON_TEXT_ON = [
  '--as-surface',
  '--as-surface-raised',
  '--as-canvas-sunken',
  '--as-graph-node-bg',
  '--as-flow-node-bg',
];

function surfaces(vars: Vars): Map<string, Rgba> {
  const out = new Map<string, Rgba>();
  for (const name of SURFACES) out.set(name, parse(value(vars, name)));
  for (const tint of TINTS) {
    for (const under of TINTED_ON) {
      out.set(`${tint} over ${under}`, over(parse(value(vars, tint)), out.get(under) as Rgba));
    }
  }
  return out;
}

function below(vars: Vars, tokens: string[], on: Map<string, Rgba>, min: number): string[] {
  const failures: string[] = [];
  for (const token of tokens) {
    const color = parse(value(vars, token));
    for (const [surface, background] of on) {
      const r = ratio(over(color, background), background);
      if (r < min) failures.push(`${token} on ${surface}: ${r.toFixed(2)}:1`);
    }
  }
  return failures;
}

describe.each<Scheme>(['light', 'dark'])('contrast in the %s scheme', (scheme) => {
  const vars = schemeVars(scheme);
  const all = surfaces(vars);

  it(`gives every text token ${TEXT_MIN}:1 on every surface`, () => {
    expect(below(vars, TEXT, all, TEXT_MIN)).toEqual([]);
  });

  it(`gives every syntax colour ${TEXT_MIN}:1 on the surfaces the editor draws it on`, () => {
    const on = new Map(CODE_ON.map((name) => [name, all.get(name) as Rgba]));
    expect(below(vars, CODE_TEXT, on, TEXT_MIN)).toEqual([]);
  });

  it(`gives every state mark and chart series ${NON_TEXT_MIN}:1 on the surfaces it is drawn on`, () => {
    const on = new Map(NON_TEXT_ON.map((name) => [name, all.get(name) as Rgba]));
    expect(below(vars, NON_TEXT, on, NON_TEXT_MIN)).toEqual([]);
  });

  it(`keeps a filled control's label at ${TEXT_MIN}:1, resting and hovered`, () => {
    const shade = theme.primaryShade as Record<Scheme, number>;
    const white: Rgba = [255, 255, 255, 1];
    const black: Rgba = [0, 0, 0, 1];
    const failures: string[] = [];
    for (const name of ['cobalt', 'amber', 'signal']) {
      for (const step of [shade[scheme], shade[scheme] + 1]) {
        const fill = parse(mantine.colors[name][step]);
        // What `autoContrast` does: black on a light fill, white on a dark one.
        const label = luminance(fill) > (theme.luminanceThreshold ?? 0.3) ? black : white;
        const r = ratio(label, fill);
        if (r < TEXT_MIN) failures.push(`${name}-${step} filled: ${r.toFixed(2)}:1`);
      }
    }
    expect(failures).toEqual([]);
  });
});

describe('the token layer', () => {
  it('keeps every chart series off the accent', () => {
    for (const scheme of ['light', 'dark'] as const) {
      const vars = schemeVars(scheme);
      const accent = value(vars, '--as-accent');
      for (const series of ['--as-chart-1', '--as-chart-2', '--as-chart-3', '--as-chart-4']) {
        expect(value(vars, series)).not.toBe(accent);
      }
    }
  });
});
