import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  EMPTY_TABLE_STATE,
  orderColumns,
  parseTableState,
  readTableState,
  useTableState,
  withMovedColumn,
  withoutWidth,
  withoutWidths,
  withVisibility,
  withWidth,
} from './tableState.ts';

const state = (over: Record<string, unknown> = {}) => ({ v: 1, widths: {}, hidden: [], shown: [], order: [], ...over });

afterEach(() => {
  localStorage.clear();
  vi.restoreAllMocks();
});

describe('parseTableState', () => {
  it('reads a stored state', () => {
    const stored = state({ widths: { name: 200.4 }, hidden: ['a'], shown: ['b'], order: ['b', 'a'] });
    expect(parseTableState(JSON.stringify(stored))).toEqual({ ...stored, widths: { name: 200 } });
  });

  it.each([null, '', 'not json', '5', '"x"', 'null', JSON.stringify({ widths: { a: 100 } }), JSON.stringify({ v: 2 })])(
    'starts empty from %j, which is not a state of this shape',
    (raw) => {
      expect(parseTableState(raw)).toEqual(EMPTY_TABLE_STATE);
    },
  );

  it('drops widths it cannot trust and lists that are not lists of ids', () => {
    const raw = JSON.stringify(
      state({ widths: { a: 'wide', b: 10, c: 1e9, d: Infinity, e: 120 }, hidden: 'a', shown: [1, 'x'], order: {} }),
    );
    expect(parseTableState(raw)).toEqual(state({ widths: { e: 120 }, shown: ['x'] }));
  });
});

describe('readTableState', () => {
  it('has nothing without a key', () => {
    expect(readTableState(undefined)).toEqual(EMPTY_TABLE_STATE);
  });

  it('reads as.table.<key>, and nothing from the old grid keys', () => {
    localStorage.setItem('as.table.queues', JSON.stringify(state({ widths: { name: 150 } })));
    localStorage.setItem('as.grid.topics', JSON.stringify({ name: 150 }));
    expect(readTableState('queues').widths).toEqual({ name: 150 });
    expect(readTableState('topics')).toEqual(EMPTY_TABLE_STATE);
  });

  it('has nothing when storage is unavailable', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    expect(readTableState('queues')).toEqual(EMPTY_TABLE_STATE);
  });
});

describe('changing a state', () => {
  it('sets a width within what a column can be, and puts it back to fitting', () => {
    const set = withWidth(EMPTY_TABLE_STATE, 'a', 123.6);
    expect(set.widths).toEqual({ a: 124 });
    expect(withWidth(set, 'a', 5).widths).toEqual({ a: 48 });
    expect(withWidth(set, 'a', 1e9).widths).toEqual({ a: 4000 });
    expect(withoutWidth(withWidth(set, 'b', 100), 'a').widths).toEqual({ b: 100 });
    expect(withoutWidths(set).widths).toEqual({});
  });

  it('shows a column and keeps it shown, or hides it', () => {
    const hidden = withVisibility(EMPTY_TABLE_STATE, 'a', false);
    expect(hidden).toMatchObject({ hidden: ['a'], shown: [] });
    const shown = withVisibility(hidden, 'a', true);
    expect(shown).toMatchObject({ hidden: [], shown: ['a'] });
    expect(withVisibility(shown, 'a', false)).toMatchObject({ hidden: ['a'], shown: [] });
  });

  it('orders the columns the viewer listed first, ignoring ids that are gone', () => {
    const cols = [{ id: 'a' }, { id: 'b' }, { id: 'c' }];
    expect(orderColumns(cols, [])).toBe(cols);
    expect(orderColumns(cols, ['c', 'gone', 'b']).map((c) => c.id)).toEqual(['a', 'c', 'b']);
  });

  it('keeps the first declared column first, whatever the stored order says', () => {
    const cols = [{ id: 'a' }, { id: 'b' }, { id: 'c' }];
    expect(orderColumns(cols, ['c', 'a', 'b']).map((c) => c.id)).toEqual(['a', 'c', 'b']);
    expect(orderColumns([], ['a'])).toEqual([]);
  });

  it('moves a column one place earlier or later, never before the first or past the last', () => {
    const ids = ['a', 'b', 'c', 'd'];
    const start = EMPTY_TABLE_STATE;
    expect(withMovedColumn(start, ids, 'c', -1).order).toEqual(['a', 'c', 'b', 'd']);
    expect(withMovedColumn(start, ids, 'b', 1).order).toEqual(['a', 'c', 'b', 'd']);
    expect(withMovedColumn(start, ids, 'b', -1)).toBe(start);
    expect(withMovedColumn(start, ids, 'a', 1)).toBe(start);
    expect(withMovedColumn(start, ids, 'd', 1)).toBe(start);
    expect(withMovedColumn(start, ids, 'gone', 1)).toBe(start);
  });
});

describe('useTableState', () => {
  it('writes every change through to storage', () => {
    const { result } = renderHook(() => useTableState('queues'));
    act(() => result.current[1]((s) => withWidth(s, 'a', 200)));

    expect(result.current[0].widths).toEqual({ a: 200 });
    expect(JSON.parse(localStorage.getItem('as.table.queues')!)).toEqual(state({ widths: { a: 200 } }));
  });

  it('reads storage again when the key changes', () => {
    localStorage.setItem('as.table.queues', JSON.stringify(state({ widths: { a: 200 } })));
    localStorage.setItem('as.table.topics', JSON.stringify(state({ widths: { a: 320 } })));
    const { result, rerender } = renderHook(({ k }) => useTableState(k), { initialProps: { k: 'queues' } });
    expect(result.current[0].widths).toEqual({ a: 200 });

    rerender({ k: 'topics' });
    expect(result.current[0].widths).toEqual({ a: 320 });

    // A change after the switch belongs to the new key.
    act(() => result.current[1]((s) => withWidth(s, 'b', 100)));
    expect(JSON.parse(localStorage.getItem('as.table.topics')!).widths).toEqual({ a: 320, b: 100 });
    expect(JSON.parse(localStorage.getItem('as.table.queues')!).widths).toEqual({ a: 200 });
  });

  it('keeps the state for the visit when there is no key, and writes nothing', () => {
    const { result } = renderHook(() => useTableState(undefined));
    act(() => result.current[1]((s) => withWidth(s, 'a', 200)));

    expect(result.current[0].widths).toEqual({ a: 200 });
    expect(Object.keys(localStorage).filter((k) => k.startsWith('as.table'))).toEqual([]);
  });

  it('keeps working when storage refuses a write', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('full');
    });
    const { result } = renderHook(() => useTableState('queues'));
    act(() => result.current[1]((s) => withWidth(s, 'a', 200)));
    expect(result.current[0].widths).toEqual({ a: 200 });
  });
});
