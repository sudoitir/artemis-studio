import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type RefObject } from 'react';

import { KIND_PRESETS, type Column } from './columns.ts';

/** What a column is, once its kind's preset and its own overrides are resolved. */
export interface ColumnSpec {
  /** Bounds in `ch`; a column without them is as wide as its content. */
  min?: number;
  max?: number;
  grow: boolean;
  /** Whether the solver may take it below its content width, by shortening or wrapping the value. */
  truncates: boolean;
  truncate: 'end' | 'middle' | 'none';
  mono: boolean;
  /** Text-like kinds are the ones whose longest values are sampled. */
  textLike: boolean;
  /** Figures and times: tabular digits. */
  tabular: boolean;
  alignEnd: boolean;
  /** Drawn as a `StatusBadge`: its padding and border are measured with the text. */
  badge: boolean;
}

export function columnSpec<T>(column: Column<T>): ColumnSpec {
  const preset = KIND_PRESETS[column.kind];
  return {
    min: column.min ?? preset.min,
    max: column.max ?? preset.max,
    grow: column.grow ?? preset.grow,
    truncates: preset.truncate !== 'none' || Boolean(column.wrap),
    truncate: preset.truncate,
    mono: preset.mono,
    textLike: column.kind === 'text' || column.kind === 'identifier' || column.kind === 'code',
    tabular: column.kind === 'number' || column.kind === 'time',
    alignEnd: column.kind === 'number',
    badge: Boolean(column.badge),
  };
}

/** The text of a cell whose column draws nothing of its own: the value, or nothing for none. */
export function cellText(value: unknown): string {
  // Accessors return scalars; an object here would be a column that needs its own `cell`.
  return String((value ?? '') as string | number | boolean | bigint);
}

/** How many rows from the top of the loaded page the Measurer sizes the columns by. */
const SAMPLE_ROWS = 40;
/** How many of the longest values of each text-like column it adds beyond them. */
const LONGEST_VALUES = 5;
/** Live growth is applied at most this often. */
const GROW_INTERVAL = 2000;
/** Added to a measured width: layout widths come back as whole pixels, and a value rounded down would be clipped. */
const ROUNDING = 1;

/**
 * What the Measurer renders: the text of the first rows of the page, then the longest values of each
 * text-like column beyond them, one row each with the other cells empty. One string per column.
 */
export function sampleRows<T>(columns: Column<T>[], data: readonly T[]): string[][] {
  const head = data.slice(0, SAMPLE_ROWS).map((row) => columns.map((column) => cellText(column.accessor(row))));
  const longest = columns.flatMap((column, index) => {
    if (!columnSpec(column).textLike) return [];
    const top: string[] = [];
    for (let i = SAMPLE_ROWS; i < data.length; i++) {
      const text = cellText(column.accessor(data[i]));
      if (top.length < LONGEST_VALUES || text.length > top.at(-1)!.length) {
        top.push(text);
        top.sort((a, b) => b.length - a.length);
        top.length = Math.min(top.length, LONGEST_VALUES);
      }
    }
    return top.map((text) => columns.map((_, i) => (i === index ? text : '')));
  });
  return [...head, ...longest];
}

/** The px of the things a `ch` or a `rem` stands for, which the solver needs and only layout knows. */
export interface Metrics {
  /** One `ch` of the UI typeface and of the monospace one. */
  ch: number;
  mono: number;
  /** A cell's padding on both sides. */
  pad: number;
  /** The select and actions columns. */
  select: number;
  actions: number;
}

/** What the solver assumes before anything is measured, and where there is no layout to measure (jsdom). */
const DEFAULT_METRICS: Metrics = { ch: 8, mono: 8, pad: 16, select: 40, actions: 44 };

interface Measurement {
  metrics: Metrics;
  /** The px each column's content needs, padding included and already clamped to its bounds. */
  intrinsic: Record<string, number>;
}

const NOT_MEASURED: Measurement = { metrics: DEFAULT_METRICS, intrinsic: {} };

/** Reads the Measurer's layout: the probes, then the header row, which is where each track's width shows. */
export function readMeasurement(root: HTMLElement | null, ids: readonly string[]): Measurement | null {
  if (!root) return null;
  const probe = (name: string) => root.querySelector<HTMLElement>(`[data-probe="${name}"]`)?.clientWidth ?? 0;
  const ch = probe('ch') / 100;
  if (ch === 0) return NOT_MEASURED;
  const heads = root.querySelectorAll<HTMLElement>('[data-measure-head]');
  const intrinsic: Record<string, number> = {};
  ids.forEach((id, i) => {
    intrinsic[id] = (heads[i]?.clientWidth ?? 0) + ROUNDING;
  });
  return {
    metrics: {
      ch,
      mono: probe('mono') / 100,
      pad: probe('pad'),
      select: probe('select'),
      actions: probe('actions'),
    },
    intrinsic,
  };
}

function widen(prev: Measurement, next: Measurement): Measurement {
  const intrinsic = { ...next.intrinsic };
  for (const [id, width] of Object.entries(prev.intrinsic)) {
    intrinsic[id] = Math.max(width, intrinsic[id] ?? 0);
  }
  return { metrics: next.metrics, intrinsic };
}

/** What the Measurer draws for one column. */
export interface MeasureColumn {
  id: string;
  header: string;
  sortable: boolean;
  spec: ColumnSpec;
}

interface MeasurementInput<T> {
  columns: Column<T>[];
  data: readonly T[];
  density: string;
  /** Whether the viewer is in the table, so live growth must wait. */
  isBusy: () => boolean;
  /**
   * Whether the table has a width, so its Measurer has a layout to read. A table inside a hidden tab
   * has none; it is measured when it is first shown, never from a read that saw nothing.
   */
  shown: boolean;
}

interface MeasurementResult {
  measurement: Measurement;
  /** The props of the Measurer, which the table renders inside its frame so it inherits the table's type. */
  measurerProps: { fields: MeasureColumn[]; sample: string[][]; rootRef: RefObject<HTMLDivElement | null> };
  /** Measures again, at once and from scratch (a double-click on a column border). */
  refit: () => void;
  /** Applies growth that was held back, once the viewer has left the table. */
  resume: () => void;
}

/**
 * Measures the columns when, and only when, ADR-0161 says to, in a layout effect so it is before
 * paint: the first non-empty data, a change to the column set, fonts finishing loading, a density
 * change, and an explicit refit. Rows that arrive later can only widen columns, at most every
 * 2 seconds, and never while the viewer is reading the table.
 */
export function useMeasurement<T>({
  columns,
  data,
  density,
  isBusy,
  shown,
}: Readonly<MeasurementInput<T>>): MeasurementResult {
  const rootRef = useRef<HTMLDivElement>(null);
  const [measurement, setMeasurement] = useState(NOT_MEASURED);
  const [fontsEpoch, setFontsEpoch] = useState(0);
  const [refitEpoch, setRefitEpoch] = useState(0);

  const fields = useMemo<MeasureColumn[]>(
    () =>
      columns.map((c) => ({
        id: c.id,
        header: c.short ?? c.header,
        sortable: Boolean(c.sortKey),
        spec: columnSpec(c),
      })),
    [columns],
  );
  const sample = useMemo(() => sampleRows(columns, data), [columns, data]);
  const ids = useMemo(() => columns.map((c) => c.id), [columns]);
  const idsRef = useRef(ids);
  idsRef.current = ids;
  const busyRef = useRef(isBusy);
  busyRef.current = isBusy;

  const columnKey = columns
    .map((c) => [c.id, c.header, c.short, c.kind, c.badge, c.min, c.max, c.sortKey ? 1 : 0].join(':'))
    .join('|');
  // The headers are measured on their own, so an empty table still gives each column its width; the
  // first rows, a table becoming visible and the other triggers each measure again.
  const key = `${columnKey}#${density}#${fontsEpoch}#${refitEpoch}#${data.length > 0}`;

  const measuredKey = useRef<string>(undefined);
  const grownFor = useRef(data);
  const lastGrowth = useRef(0);
  useLayoutEffect(() => {
    if (!shown || measuredKey.current === key) return;
    const next = readMeasurement(rootRef.current, idsRef.current);
    if (!next || next === NOT_MEASURED) return;
    measuredKey.current = key;
    grownFor.current = data;
    setMeasurement(next);
    lastGrowth.current = Date.now();
  }, [data, key, shown]);

  useEffect(() => {
    const fonts = document.fonts;
    const bump = () => setFontsEpoch((epoch) => epoch + 1);
    if (fonts.status !== 'loaded') void fonts.ready?.then(bump);
    fonts.addEventListener('loadingdone', bump);
    return () => fonts.removeEventListener('loadingdone', bump);
  }, []);

  const pending = useRef(false);
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined);
  const requestGrowth = useCallback(() => {
    pending.current = true;
    if (timer.current !== undefined) return;
    const attempt = () => {
      timer.current = undefined;
      if (!pending.current || busyRef.current()) return;
      pending.current = false;
      lastGrowth.current = Date.now();
      const next = readMeasurement(rootRef.current, idsRef.current);
      if (next) setMeasurement((prev) => widen(prev, next));
    };
    const wait = lastGrowth.current + GROW_INTERVAL - Date.now();
    if (wait <= 0) attempt();
    else timer.current = setTimeout(attempt, wait);
  }, []);

  // Only new rows ask for growth; the triggers above measure on their own.
  useEffect(() => {
    if (grownFor.current === data) return;
    grownFor.current = data;
    if (data.length > 0 && measuredKey.current === key) requestGrowth();
  }, [data, key, requestGrowth]);
  useEffect(() => () => clearTimeout(timer.current), []);

  const resume = useCallback(() => {
    if (pending.current) requestGrowth();
  }, [requestGrowth]);
  const refit = useCallback(() => setRefitEpoch((epoch) => epoch + 1), []);

  return { measurement, measurerProps: { fields, sample, rootRef }, refit, resume };
}

/**
 * Reports the inline size of an element now and whenever it changes, from one ResizeObserver that
 * is throttled to a frame. Never from scroll. Reads `clientWidth`, so where there is no layout
 * (jsdom) it is 0, which the solver takes as "show every column at its base".
 */
export function useInlineSize(ref: RefObject<HTMLElement | null>, onSize: (px: number) => void) {
  useLayoutEffect(() => {
    const element = ref.current;
    if (!element) return;
    onSize(element.clientWidth);
    let frame = 0;
    const observer = new ResizeObserver(() => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(() => onSize(element.clientWidth));
    });
    observer.observe(element);
    return () => {
      cancelAnimationFrame(frame);
      observer.disconnect();
    };
  }, [ref, onSize]);
}
