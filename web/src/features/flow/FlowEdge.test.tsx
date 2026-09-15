import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/react';

import { FlowDots } from './FlowEdge.tsx';
import { edgeText } from './flowFormat.ts';

describe('flow edge', () => {
  it('states delivery, rate and faults in words', () => {
    expect(
      edgeText({ kind: 'ROUTE', delivery: 'COPY', rate: 12.5, rateSource: 'QUEUE_METRIC', stale: false, faults: [] }),
    ).toBe('copy · 12.5 msg/s');
    expect(edgeText({ kind: 'CONSUME', rateSource: 'SAMPLER', stale: true, faults: ['STALLED'] })).toBe(
      'measuring… · stalled · stale',
    );
  });

  it('draws the dots it was given, spread along the path', () => {
    const { container } = render(
      <svg>
        <FlowDots path="M0,0 L100,0" seconds={3} count={3} />
      </svg>,
    );

    const motions = container.querySelectorAll('animateMotion');
    expect(motions).toHaveLength(3);
    expect([...motions].map((m) => m.getAttribute('begin'))).toEqual(['0s', '-1s', '-2s']);
    expect(motions[0].getAttribute('dur')).toBe('3s');
  });
});
