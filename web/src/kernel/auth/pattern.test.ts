import { describe, expect, it } from 'vitest';

import { matchesPattern } from './pattern.ts';

describe('matchesPattern', () => {
  it.each([
    ['orders.#', 'orders', true],
    ['orders.#', 'orders.in.dlq', true],
    ['orders.*', 'orders', false],
    ['orders.*', 'orders.in', true],
    ['orders.*', 'orders.in.dlq', false],
    ['#', 'anything.at.all', true],
    ['orders.*.dlq', 'orders.in.dlq', true],
    ['orders.*.dlq', 'orders.dlq', false],
    ['orders.in', 'orders.in', true],
    ['orders.in', 'orders.out', false],
    ['#.dlq', 'dlq', true],
    ['#.dlq', 'orders.in.dlq', true],
    ['#.dlq', 'orders.in', false],
  ])('%s against %s is %s', (pattern, name, expected) => {
    expect(matchesPattern(pattern, name)).toBe(expected);
  });
});
