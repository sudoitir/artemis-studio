import type { ColumnPriority } from './columns.ts';

/** A column as the solver sees it: every width in px. */
export interface SolverColumn {
  id: string;
  /** The measured width of the column's content. */
  intrinsic: number;
  min: number;
  max: number;
  grow: boolean;
  /** Whether its values can be shortened, so the column may go down to `min`. */
  truncates: boolean;
  priority: ColumnPriority;
  /** A width the operator set. It is kept exactly: never clamped and never shrunk. */
  userWidth?: number;
  /** The operator chose to show this column, so it is never hidden. */
  userShown?: boolean;
  /** Never hidden, whatever its priority (a node-attribution column). */
  essential?: boolean;
}

export interface SolveResult {
  /** `grid-template-columns` for the visible data columns, in order. The select and actions columns are the renderer's. */
  template: string;
  /** The px width each visible column gets once the grid is laid out, keyed by column id. */
  widths: Record<string, number>;
  /** The ids of the hidden columns, in column order. */
  hidden: string[];
  /** Even the shortest widths do not fit: the table scrolls sideways. */
  overflow: boolean;
}

/** A column hidden before is shown again only when it fits with this much to spare, so a resize does not flap. */
const HYSTERESIS = 16;

interface Track {
  col: SolverColumn;
  index: number;
  base: number;
  floor: number;
  flexible: boolean;
}

const sum = (values: number[]) => values.reduce((total, value) => total + value, 0);

function toTrack(col: SolverColumn, index: number): Track {
  if (col.userWidth !== undefined) {
    return { col, index, base: col.userWidth, floor: col.userWidth, flexible: false };
  }
  const base = Math.ceil(Math.min(Math.max(col.intrinsic, col.min), col.max));
  const floor = col.truncates ? Math.min(base, Math.ceil(col.min)) : base;
  return { col, index, base, floor, flexible: col.grow };
}

/** The px each track gets when `room` is all there is: base when it fits, else the truncatable ones shrink in proportion to what they can give up. */
function fit(tracks: Track[], room: number): number[] {
  const need = sum(tracks.map((t) => t.base)) - room;
  if (need <= 0) return tracks.map((t) => t.base);
  const give = sum(tracks.map((t) => t.base - t.floor));
  const ratio = give === 0 ? 1 : Math.min(need / give, 1);
  return tracks.map((t) => Math.floor(t.base - (t.base - t.floor) * ratio));
}

/** What CSS gives `minmax(min, 1fr)` tracks: the flexible ones share what is left equally, and one whose minimum is above its share keeps its minimum. */
function spread(tracks: Track[], mins: number[], room: number): number[] {
  let flexible = tracks.flatMap((t, i) => (t.flexible ? [i] : []));
  let left = room - sum(mins.filter((_, i) => !flexible.includes(i)));
  for (;;) {
    const share = left / flexible.length;
    const frozen = flexible.filter((i) => mins[i] > share);
    if (frozen.length === 0) return mins.map((min, i) => (flexible.includes(i) ? share : min));
    left -= sum(frozen.map((i) => mins[i]));
    flexible = flexible.filter((i) => !frozen.includes(i));
  }
}

/**
 * Lays the columns out in `W` px, the grid's inline size; `fixed` is the px of the select and actions
 * columns the renderer adds beside them. Pure, and the only place the table decides what fits.
 *
 * 1. A width the operator set is a hard track.
 * 2. When everything fits, grow columns get `minmax(base, 1fr)` and the rest their base.
 * 3. Otherwise truncatable columns shrink toward their minimum.
 * 4. Otherwise `low`, then `high`, columns are hidden, inline end first. `prev` supplies the
 *    hysteresis. Essential columns, the first data column and columns the operator chose to show stay.
 * 5. Otherwise the minimums are used and the table overflows.
 *
 * `W === 0` (no layout yet, as in jsdom) shows every column at its base.
 */
export function solveColumns(input: {
  W: number;
  cols: SolverColumn[];
  prev?: SolveResult;
  fixed?: number;
}): SolveResult {
  const { W, cols, prev, fixed = 0 } = input;
  const all = cols.map(toTrack);
  const prevHidden = new Set(prev?.hidden);
  const hideable = (t: Track) => t.index > 0 && !t.col.essential && t.col.priority !== 'essential' && !t.col.userShown;
  const hideOrder = (['low', 'high'] as const).flatMap((priority) =>
    all.filter((t) => hideable(t) && t.col.priority === priority).reverse(),
  );

  let visible = all;
  let room = W - fixed;
  let overflow = false;
  if (W === 0) {
    room = sum(all.map((t) => t.base));
  } else {
    for (;;) {
      const margin = visible.some((t) => hideable(t) && prevHidden.has(t.col.id)) ? HYSTERESIS : 0;
      if (sum(visible.map((t) => t.floor)) <= room - margin) break;
      const next = hideOrder.find((t) => visible.includes(t));
      if (!next) {
        overflow = sum(visible.map((t) => t.floor)) > room;
        break;
      }
      visible = visible.filter((t) => t !== next);
    }
  }

  const mins = fit(visible, room);
  const px = spread(visible, mins, Math.max(room, sum(mins)));
  return {
    template: visible.map((t, i) => (t.flexible ? `minmax(${mins[i]}px, 1fr)` : `${mins[i]}px`)).join(' '),
    widths: Object.fromEntries(visible.map((t, i) => [t.col.id, px[i]])),
    hidden: all.filter((t) => !visible.includes(t)).map((t) => t.col.id),
    overflow,
  };
}
