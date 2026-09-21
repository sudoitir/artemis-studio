import { describe, expect, it } from 'vitest';

import { composes, ENDS, STARTS, type RoutingKind } from './routingGraph.ts';

const KINDS: RoutingKind[] = ['address', 'queue', 'divert', 'bridge', 'target'];

describe('composes', () => {
  it('proposes a divert between two addresses and a bridge from a queue to a target, and nothing else', () => {
    const pairs = KINDS.flatMap((from) => KINDS.map((to) => [from, to, composes(from, to)] as const)).filter(
      ([, , what]) => what !== null,
    );
    expect(pairs).toEqual([
      ['address', 'address', 'divert'],
      ['queue', 'target', 'bridge'],
    ]);
  });

  it('offers a start or an end only on a kind that takes part in some composition', () => {
    for (const kind of KINDS) {
      expect(STARTS.has(kind)).toBe(KINDS.some((to) => composes(kind, to) !== null));
      expect(ENDS.has(kind)).toBe(KINDS.some((from) => composes(from, kind) !== null));
    }
  });
});
