import { describe, expect, it } from 'vitest';

import { nextCell, resolveRow } from './rovingGrid.ts';

const shape = { rows: 10, cols: 4, page: 3 };

describe('nextCell', () => {
  it('moves by one cell with the arrows and keeps the column up and down', () => {
    expect(nextCell({ row: 2, col: 1 }, { key: 'ArrowRight' }, shape)).toEqual({ row: 2, col: 2 });
    expect(nextCell({ row: 2, col: 1 }, { key: 'ArrowLeft' }, shape)).toEqual({ row: 2, col: 0 });
    expect(nextCell({ row: 2, col: 1 }, { key: 'ArrowDown' }, shape)).toEqual({ row: 3, col: 1 });
    expect(nextCell({ row: 2, col: 1 }, { key: 'ArrowUp' }, shape)).toEqual({ row: 1, col: 1 });
  });

  it('stops at the edges instead of wrapping', () => {
    expect(nextCell({ row: 0, col: 0 }, { key: 'ArrowUp' }, shape)).toEqual({ row: 0, col: 0 });
    expect(nextCell({ row: 10, col: 3 }, { key: 'ArrowDown' }, shape)).toEqual({ row: 10, col: 3 });
    expect(nextCell({ row: 4, col: 3 }, { key: 'ArrowRight' }, shape)).toEqual({ row: 4, col: 3 });
  });

  it('reaches the header row from the first body row', () => {
    expect(nextCell({ row: 1, col: 2 }, { key: 'ArrowUp' }, shape)).toEqual({ row: 0, col: 2 });
  });

  it('goes to the row ends with Home and End, and to the first and last row with Ctrl', () => {
    expect(nextCell({ row: 5, col: 2 }, { key: 'Home' }, shape)).toEqual({ row: 5, col: 0 });
    expect(nextCell({ row: 5, col: 2 }, { key: 'End' }, shape)).toEqual({ row: 5, col: 3 });
    expect(nextCell({ row: 5, col: 2 }, { key: 'Home', ctrlKey: true }, shape)).toEqual({ row: 1, col: 2 });
    expect(nextCell({ row: 5, col: 2 }, { key: 'End', metaKey: true }, shape)).toEqual({ row: 10, col: 2 });
  });

  it('pages without leaving the body for the header', () => {
    expect(nextCell({ row: 5, col: 0 }, { key: 'PageDown' }, shape)).toEqual({ row: 8, col: 0 });
    expect(nextCell({ row: 9, col: 0 }, { key: 'PageDown' }, shape)).toEqual({ row: 10, col: 0 });
    expect(nextCell({ row: 2, col: 0 }, { key: 'PageUp' }, shape)).toEqual({ row: 1, col: 0 });
  });

  it('mirrors left and right in a right-to-left page', () => {
    expect(nextCell({ row: 1, col: 1 }, { key: 'ArrowLeft' }, { ...shape, rtl: true })).toEqual({ row: 1, col: 2 });
  });

  it('ignores keys that do not move', () => {
    expect(nextCell({ row: 1, col: 1 }, { key: 'Enter' }, shape)).toBeNull();
  });
});

describe('resolveRow', () => {
  it('follows a row to its new position', () => {
    expect(resolveRow(['a', 'b', 'c'], 'c', 1)).toBe(3);
  });

  it('hands a removed row to its nearest neighbour', () => {
    expect(resolveRow(['a', 'c'], 'b', 2)).toBe(2);
    expect(resolveRow(['a'], 'z', 5)).toBe(1);
  });

  it('keeps the header and an empty grid on the header', () => {
    expect(resolveRow(['a'], null, 0)).toBe(0);
    expect(resolveRow([], 'a', 3)).toBe(0);
  });
});
