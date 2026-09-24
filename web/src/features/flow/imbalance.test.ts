import { describe, expect, it } from 'vitest';

import type { FlowNodeShare } from './api.ts';
import { imbalance } from './imbalance.ts';

function share(node: string, over: Partial<FlowNodeShare> = {}): FlowNodeShare {
  return { nodeId: node, node, messageCount: 0, consumerCount: 1, inRate: 0, outRate: 0, stale: false, ...over };
}

describe('imbalance', () => {
  it.each<[string, FlowNodeShare[], string[]]>([
    [
      'a stranded backlog names where the consumers are',
      [share('artemis-a', { messageCount: 10, consumerCount: 3 }), share('artemis-b', { messageCount: 9000, consumerCount: 0 })],
      [
        '100% of the backlog is on artemis-b.',
        'artemis-b holds 9,000 messages and has no consumer; the consumers are on artemis-a.',
      ],
    ],
    [
      'concentration at 75% of a backlog of 100',
      [share('a', { messageCount: 75 }), share('b', { messageCount: 25 })],
      ['75% of the backlog is on a.'],
    ],
    ['no concentration below 100 messages', [share('a', { messageCount: 90 }), share('b', { messageCount: 5 })], ['Balanced across 2 nodes.']],
    ['no concentration just under 75%', [share('a', { messageCount: 74 }), share('b', { messageCount: 26 })], ['Balanced across 2 nodes.']],
    [
      'skew of 40 points or more',
      [share('a', { inRate: 9, outRate: 5 }), share('b', { inRate: 1, outRate: 5 })],
      ['a receives 90% of messages in but delivers 50% of messages out.'],
    ],
    [
      'no skew under 1 msg/s',
      [share('a', { inRate: 0.9, outRate: 0.1 }), share('b', { inRate: 0, outRate: 0.8 })],
      ['Balanced across 2 nodes.'],
    ],
    [
      'no stranded backlog when no node has a consumer',
      [share('a', { messageCount: 50, consumerCount: 0 }), share('b', { messageCount: 50, consumerCount: 0 })],
      ['Balanced across 2 nodes.'],
    ],
    ['one node is not called balanced across one', [share('a', { messageCount: 500 })], ['Served by one node, a.']],
    [
      'an unanswered node is unknown and out of the percentages',
      [share('a', { messageCount: 80 }), share('b', { messageCount: 30 }), share('c', { stale: true, messageCount: null })],
      ['c did not answer, so its share is unknown and left out.', 'Balanced across 2 nodes.'],
    ],
  ])('%s', (_, shares, expected) => {
    expect(imbalance(shares).map((s) => s.text)).toEqual(expected);
  });

  it('counts a single message in the singular', () => {
    const texts = imbalance([share('a', { consumerCount: 2 }), share('b', { messageCount: 1, consumerCount: 0 })]).map(
      (s) => s.text,
    );
    expect(texts).toContain('b holds 1 message and has no consumer; the consumers are on a.');
  });
});
