import { describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../test/render.tsx';
import { EmptyState } from './EmptyState.tsx';

describe('EmptyState', () => {
  it('says what the resource is and offers the action that creates one', () => {
    renderWithProviders(
      <EmptyState
        kind="empty"
        title="No queues"
        description="A queue holds messages for consumers. Queues appear when an address is created."
        action={<button type="button">Create queue</button>}
      />,
    );
    const status = screen.getByRole('region', { name: 'No queues' });
    expect(within(status).getByText('No queues')).toBeInTheDocument();
    expect(within(status).getByText(/holds messages for consumers/)).toBeInTheDocument();
    expect(within(status).getByRole('button', { name: 'Create queue' })).toBeInTheDocument();
    expect(within(status).queryByRole('button', { name: 'Clear filters' })).not.toBeInTheDocument();
  });

  it('offers no action when the operator may not take one', () => {
    renderWithProviders(<EmptyState kind="empty" title="No queues" description="A queue holds messages." />);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('says a filter caused the emptiness and clears it on request', async () => {
    const onClearFilters = vi.fn();
    renderWithProviders(<EmptyState kind="filtered" title="No queues match" onClearFilters={onClearFilters} />);
    expect(screen.getByText('No rows match the current filters.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Clear filters' }));
    expect(onClearFilters).toHaveBeenCalledOnce();
  });

  it('names the nodes it could not reach instead of calling the result empty', () => {
    renderWithProviders(<EmptyState kind="unreachable" title="Queues unavailable" nodes={['broker-a', 'broker-b']} />);
    const nodes = screen.getByRole('list', { name: 'Nodes that could not be reached' });
    expect(
      within(nodes)
        .getAllByRole('listitem')
        .map((item) => item.textContent),
    ).toEqual(['broker-a', 'broker-b']);
    expect(screen.getByText(/may not be everything/)).toBeInTheDocument();
  });
});
