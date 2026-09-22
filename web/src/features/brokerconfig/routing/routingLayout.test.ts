import type { Node } from '@xyflow/react';
import { describe, expect, it } from 'vitest';

import { neighbour, readingOrder } from './routingLayout.ts';

const card = (id: string, x: number, y: number): Node => ({ id, position: { x, y }, width: 200, height: 80, data: {} });

/*
 *   a ── b ── c
 *   d ── e
 *        f
 */
const GRID = [
  card('a', 0, 0),
  card('b', 300, 0),
  card('c', 600, 0),
  card('d', 0, 120),
  card('e', 300, 120),
  card('f', 300, 240),
];

describe('neighbour', () => {
  it('moves to the element in the direction of the arrow, not down a column', () => {
    expect(neighbour(GRID, 'a', 'right')).toBe('b');
    expect(neighbour(GRID, 'b', 'right')).toBe('c');
    expect(neighbour(GRID, 'a', 'down')).toBe('d');
    expect(neighbour(GRID, 'e', 'up')).toBe('b');
    expect(neighbour(GRID, 'e', 'left')).toBe('d');
    expect(neighbour(GRID, 'e', 'down')).toBe('f');
  });

  it('prefers the element in line over a nearer one off to the side', () => {
    // From d, right: e is in line; b is up and to the right.
    expect(neighbour(GRID, 'd', 'right')).toBe('e');
  });

  it('stays put at the edge rather than wrapping', () => {
    expect(neighbour(GRID, 'c', 'right')).toBeNull();
    expect(neighbour(GRID, 'a', 'up')).toBeNull();
    expect(neighbour(GRID, 'missing', 'right')).toBeNull();
  });
});

describe('readingOrder', () => {
  it('reads left to right, then down', () => {
    expect(readingOrder(GRID)).toEqual(['a', 'b', 'c', 'd', 'e', 'f']);
  });
});
