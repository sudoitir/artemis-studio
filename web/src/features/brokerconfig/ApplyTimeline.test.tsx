import { describe, expect, it } from 'vitest';
import { screen, within } from '@testing-library/react';

import { renderWithProviders } from '../../test/render.tsx';
import type { ApplyProgress } from './applyProgress.ts';
import { ApplyTimeline } from './ApplyTimeline.tsx';

const frame = (over: Partial<ApplyProgress>): ApplyProgress => ({
  applyId: 1,
  nodeId: 'n-a',
  nodeName: 'broker-1',
  canary: true,
  phase: 'APPLYING',
  done: 1,
  total: 3,
  ...over,
});

describe('ApplyTimeline', () => {
  it('says it is waiting, in a polite live region, before any node has reported', () => {
    renderWithProviders(<ApplyTimeline progress={[]} />);

    expect(screen.getByRole('status')).toHaveTextContent('Applying — waiting for the first node to report…');
  });

  it('states every node’s phase in words, with how far it has got, so colour carries nothing alone', () => {
    renderWithProviders(
      <ApplyTimeline
        progress={[
          frame({}),
          frame({ nodeId: 'n-b', nodeName: 'broker-2', canary: false, phase: 'HALTED', done: 3, total: 3 }),
          frame({ nodeId: 'n-c', nodeName: 'broker-3', canary: false, phase: 'UNREACHABLE', done: 0, total: 0 }),
        ]}
      />,
    );

    const status = screen.getByRole('status');
    expect(within(status).getByText('broker-1 (canary)')).toBeInTheDocument();
    expect(within(status).getByText('applying')).toBeInTheDocument();
    expect(within(status).getByText('step 1 of 3')).toBeInTheDocument();
    expect(within(status).getByText('halted here')).toBeInTheDocument();
    expect(within(status).getByText('could not be reached')).toBeInTheDocument();
    // A node with no steps has no step count to state.
    expect(within(status).queryByText(/step 0 of 0/)).toBeNull();
  });

  it('never states more steps done than there are', () => {
    renderWithProviders(<ApplyTimeline progress={[frame({ done: 5, total: 3 })]} />);

    expect(screen.getByText('step 3 of 3')).toBeInTheDocument();
  });
});
