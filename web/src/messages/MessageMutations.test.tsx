import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../test/render.tsx';
import { server } from '../test/setup.ts';
import { ConfirmByTyping } from '../shared/ConfirmByTyping.tsx';

vi.mock('@tanstack/react-router', () => ({
  useParams: () => ({ clusterId: 'c1', queueName: 'ORDERS' }),
  useNavigate: () => () => {},
}));

const { BulkActionPreview } = await import('./BulkActionPreview.tsx');

describe('ConfirmByTyping', () => {
  it('arms the button only on an exact token match', async () => {
    const user = userEvent.setup();
    const onConfirm = vi.fn();
    renderWithProviders(
      <ConfirmByTyping token="ORDERS" confirmLabel="Do it" onConfirm={onConfirm} />,
    );
    const btn = screen.getByRole('button', { name: 'Do it' });
    expect(btn).toBeDisabled();

    await user.type(screen.getByRole('textbox'), 'ORDER');
    expect(btn).toBeDisabled();

    await user.type(screen.getByRole('textbox'), 'S');
    expect(btn).toBeEnabled();
    await user.click(btn);
    expect(onConfirm).toHaveBeenCalled();
  });
});

describe('BulkActionPreview', () => {
  it('gates "Run anyway" behind the typed queue name when over cap', async () => {
    server.use(
      http.post('*/api/v1/clusters/c1/queues/ORDERS/messages/actions/delete', () =>
        HttpResponse.json({ affectedCount: 50, cap: 10, overCap: true, node: 'n1' }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(
      <BulkActionPreview
        clusterId="c1"
        queueName="ORDERS"
        action="delete"
        opened
        onClose={() => {}}
        onDone={() => {}}
      />,
    );

    await user.type(screen.getByLabelText('Selector'), "region = 'eu'");
    await user.click(screen.getByRole('button', { name: 'Preview' }));

    expect(await screen.findByText(/≈ 50 messages/)).toBeInTheDocument();
    const runAnyway = screen.getByRole('button', { name: /Delete 50 messages anyway/i });
    expect(runAnyway).toBeDisabled();

    await user.type(screen.getByLabelText(/Type "ORDERS" to confirm/), 'ORDERS');
    expect(runAnyway).toBeEnabled();
  });
});
