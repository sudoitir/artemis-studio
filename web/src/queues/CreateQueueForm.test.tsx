import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../test/render.tsx';
import { server } from '../test/setup.ts';
import { CreateQueueForm } from './CreateQueueForm.tsx';

function emptyQueues() {
  return http.get('*/api/v1/clusters/c1/queues', () =>
    HttpResponse.json({ data: [], count: 0, page: 1, pageSize: 300 }),
  );
}

function Harness() {
  return <CreateQueueForm clusterId="c1" opened onClose={() => {}} />;
}

describe('CreateQueueForm', () => {
  it('labels every field visibly rather than relying on a placeholder', async () => {
    server.use(emptyQueues());
    renderWithProviders(<Harness />);

    expect(await screen.findByRole('textbox', { name: /address/i })).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: /queue name/i })).toBeInTheDocument();
    expect(screen.getByRole('radiogroup', { name: /routing type/i })).toBeInTheDocument();
    expect(screen.getByRole('switch', { name: /durable/i })).toBeInTheDocument();
  });

  it('validates a required field when the operator leaves it, before any submit', async () => {
    server.use(emptyQueues());
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    const name = await screen.findByRole('textbox', { name: /queue name/i });
    await user.click(name);
    await user.tab();

    expect(await screen.findByText('A queue name is required.')).toBeInTheDocument();
  });

  it('keeps submit enabled and explains the problem on activation, never silently disabled', async () => {
    server.use(emptyQueues());
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    const create = await screen.findByRole('button', { name: 'Create queue' });
    expect(create).toBeEnabled();

    await user.click(create);

    // It reported what is wrong rather than doing nothing.
    expect(await screen.findByText('A queue name is required.')).toBeInTheDocument();
    expect(screen.getByText('Fix the fields above to continue.')).toBeInTheDocument();
  });

  it('moves focus to the first invalid field on a rejected submit', async () => {
    server.use(emptyQueues());
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    await user.click(await screen.findByRole('button', { name: 'Create queue' }));

    // Address is the first field, so it is the one that gets focus.
    await waitFor(() =>
      expect(screen.getByRole('textbox', { name: /address/i })).toHaveFocus(),
    );
  });

  it('previews the per-node outcome without creating anything', async () => {
    server.use(
      emptyQueues(),
      http.post('*/api/v1/clusters/c1/queues', ({ request }) => {
        const url = new URL(request.url);
        expect(url.searchParams.get('dryRun')).toBe('true');
        return HttpResponse.json({
          dryRun: true,
          cap: 1000,
          overCap: false,
          partial: false,
          totalAffected: 0,
          nodes: [
            { nodeId: 'a', nodeName: 'node-a', status: 'WOULD_APPLY', affected: null, error: null },
            {
              nodeId: 'b',
              nodeName: 'node-b',
              status: 'SKIPPED_NOT_LIVE',
              affected: null,
              error: null,
            },
          ],
        });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    await user.type(await screen.findByRole('textbox', { name: /address/i }), 'orders');
    await user.type(screen.getByRole('textbox', { name: /queue name/i }), 'orders');
    await user.click(screen.getByRole('button', { name: 'Preview' }));

    expect(
      await screen.findByText('Would apply to 1 of 2 nodes, 1 not live and will be skipped'),
    ).toBeInTheDocument();
    expect(screen.getByText('Nothing has been created yet. This is what would happen:')).toBeInTheDocument();
  });
});
