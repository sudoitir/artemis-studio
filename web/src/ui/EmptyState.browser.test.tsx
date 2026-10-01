import type { ReactElement } from 'react';
import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { axeViolations, Frame, renderThemed, SCHEMES } from '../test/browser.tsx';
import { EmptyState } from './EmptyState.tsx';

const KINDS: Record<string, ReactElement> = {
  empty: (
    <EmptyState
      kind="empty"
      title="No queues"
      description="A queue holds messages for consumers. One exists once a producer sends to an address with a queue."
      action={<button type="button">Create queue</button>}
    />
  ),
  filtered: <EmptyState kind="filtered" title="No queues match" onClearFilters={() => {}} />,
  unreachable: <EmptyState kind="unreachable" title="No queues shown" nodes={['broker-1', 'broker-2']} />,
};

describe('EmptyState', () => {
  describe.each(SCHEMES)('in the %s scheme', (scheme) => {
    it.each(Object.keys(KINDS))('has no accessibility violations when %s', async (kind) => {
      const { container } = renderThemed(<Frame width={960}>{KINDS[kind]}</Frame>, scheme);
      expect(await axeViolations(container)).toEqual([]);
    });
  });

  it('reads top to bottom from the start edge, in a column no wider than 36rem', () => {
    renderThemed(<Frame width={960}>{KINDS.empty}</Frame>, 'light');
    const status = screen.getByRole('region', { name: 'No queues' }).getBoundingClientRect();
    const title = screen.getByText('No queues').getBoundingClientRect();
    const description = screen.getByText(/A queue holds messages/).getBoundingClientRect();
    const action = screen.getByRole('button', { name: 'Create queue' }).getBoundingClientRect();
    expect(status.width).toBeLessThanOrEqual(36 * 16 + 1);
    expect(description.top).toBeGreaterThanOrEqual(title.bottom);
    expect(action.top).toBeGreaterThanOrEqual(description.bottom);
    expect(description.left).toBeCloseTo(title.left, 0);
    expect(action.left).toBeCloseTo(title.left, 0);
  });

  it('lists the nodes it could not reach below its description', () => {
    renderThemed(<Frame width={960}>{KINDS.unreachable}</Frame>, 'light');
    const nodes = screen.getByRole('list', { name: 'Nodes that could not be reached' }).getBoundingClientRect();
    const description = screen.getByText(/could not reach these nodes/).getBoundingClientRect();
    expect(nodes.top).toBeGreaterThanOrEqual(description.bottom);
    expect(screen.getAllByRole('listitem').map((item) => item.textContent)).toEqual(['broker-1', 'broker-2']);
  });
});
