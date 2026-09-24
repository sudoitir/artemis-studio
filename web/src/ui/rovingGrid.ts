/**
 * The keyboard model of a data grid (ADR-0106): one tab stop, and the WAI-ARIA APG grid keys to
 * move between cells. Pure, so the moves are tested apart from any rendering.
 *
 * <p>Row 0 is the header row; body rows are 1…`rows`. Columns are 0…`cols - 1` in visual order.
 */

export interface GridPos {
  row: number;
  col: number;
}

export interface GridShape {
  /** Body rows; the header row is added on top as row 0. */
  rows: number;
  cols: number;
  /** Rows a Page Up / Page Down moves by. */
  page: number;
  /** In a right-to-left page, ArrowLeft moves toward the end of the row. */
  rtl?: boolean;
}

export interface GridKey {
  key: string;
  ctrlKey?: boolean;
  metaKey?: boolean;
}

const clamp = (value: number, min: number, max: number) => Math.min(Math.max(value, min), max);

/**
 * Where a key moves focus from `pos`, or null when the key is not a movement key. A move past an
 * edge stays at the edge rather than wrapping: a grid is not a ring, and wrapping from the last
 * row to the header would read as the view having jumped.
 */
export function nextCell(pos: GridPos, input: GridKey, shape: GridShape): GridPos | null {
  const lastRow = shape.rows;
  const lastCol = Math.max(shape.cols - 1, 0);
  const firstBody = shape.rows > 0 ? 1 : 0;
  const modifier = Boolean(input.ctrlKey || input.metaKey);
  const forward = shape.rtl ? 'ArrowLeft' : 'ArrowRight';
  const backward = shape.rtl ? 'ArrowRight' : 'ArrowLeft';

  switch (input.key) {
    case forward:
      return { row: pos.row, col: clamp(pos.col + 1, 0, lastCol) };
    case backward:
      return { row: pos.row, col: clamp(pos.col - 1, 0, lastCol) };
    case 'ArrowDown':
      return { row: clamp(pos.row + 1, 0, lastRow), col: pos.col };
    case 'ArrowUp':
      return { row: clamp(pos.row - 1, 0, lastRow), col: pos.col };
    case 'Home':
      return modifier ? { row: firstBody, col: pos.col } : { row: pos.row, col: 0 };
    case 'End':
      return modifier ? { row: lastRow, col: pos.col } : { row: pos.row, col: lastCol };
    case 'PageDown':
      return { row: clamp(pos.row + Math.max(shape.page, 1), 0, lastRow), col: pos.col };
    case 'PageUp':
      // From the body, a page up stops at the first row rather than landing on the header.
      return {
        row: pos.row === 0 ? 0 : clamp(pos.row - Math.max(shape.page, 1), firstBody, lastRow),
        col: pos.col,
      };
    default:
      return null;
  }
}

/**
 * The row index a remembered row key resolves to after the data changed: its new index when it is
 * still there, otherwise the nearest surviving neighbour of where it was, so that removing the
 * focused row hands focus to the row that took its place.
 */
export function resolveRow(keys: readonly string[], key: string | null, lastIndex: number): number {
  if (key === null) return 0;
  const found = keys.indexOf(key);
  if (found >= 0) return found + 1;
  if (keys.length === 0) return 0;
  return clamp(lastIndex, 1, keys.length);
}
