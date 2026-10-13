import { describe, expect, it } from 'vitest';

import type { Column } from './columns.ts';
import { findRows, findSummary, stepMatch } from './gridFind.ts';

interface Row {
  name: string;
  depth: number;
}

const columns: Column<Row>[] = [
  { id: 'name', header: 'Queue', accessor: (r) => r.name, kind: 'text', priority: 'essential' },
  { id: 'depth', header: 'Depth', accessor: (r) => r.depth, kind: 'number', priority: 'high' },
];

const data: Row[] = [
  { name: 'ORDERS', depth: 12 },
  { name: 'shipments', depth: 0 },
  { name: 'orders.dlq', depth: 431 },
];

describe('findRows', () => {
  it('lists the rows with a cell that contains the query, ignoring case', () => {
    expect(findRows(columns, data, 'orders')).toEqual([0, 2]);
  });

  it('reads numbers as the cell shows them', () => {
    expect(findRows(columns, data, '431')).toEqual([2]);
  });

  it('finds nothing for an empty query or one that matches no row', () => {
    expect(findRows(columns, data, '  ')).toEqual([]);
    expect(findRows(columns, data, 'absent')).toEqual([]);
  });
});

describe('stepMatch', () => {
  it('wraps at both ends', () => {
    expect(stepMatch(2, 3, 1)).toBe(0);
    expect(stepMatch(0, 3, -1)).toBe(2);
  });

  it('has no match to stand on when there are none', () => {
    expect(stepMatch(-1, 0, 1)).toBe(-1);
  });
});

describe('findSummary', () => {
  it('says nothing before a query, how many matches there are, or that there are none', () => {
    expect(findSummary('', 0, -1)).toBe('');
    expect(findSummary('o', 1, 0)).toBe('1 of 1 match');
    expect(findSummary('o', 12, 3)).toBe('4 of 12 matches');
    expect(findSummary('zz', 0, -1)).toBe('No matches');
  });
});
