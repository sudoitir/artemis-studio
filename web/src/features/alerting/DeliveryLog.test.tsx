import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { absoluteLabel } from '../../kernel/time/time.ts';
import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { AlertDeliveryView, NotificationChannelView } from './api.ts';
import { DeliveryLog } from './DeliveryLog.tsx';

const CHANNEL: NotificationChannelView = {
  id: 'ch-1',
  name: 'on-call',
  kind: 'WEBHOOK',
  config: '{}',
  enabled: true,
  hasSecret: false,
  boundRuleCount: 1,
};

const delivery = (seq: number, over: Partial<AlertDeliveryView> = {}): AlertDeliveryView => ({
  seq,
  ruleId: 'r-1',
  summary: `Queue orders backlog ${seq}`,
  state: 'SENT',
  attempts: 1,
  lastError: null,
  createdAt: '2026-09-11T10:00:00Z',
  nextAttemptAt: '2026-09-11T10:05:00Z',
  deliveredAt: '2026-09-11T10:01:00Z',
  ...over,
});

function serve(rows: AlertDeliveryView[] | Response) {
  server.use(
    http.get('*/api/v1/channels/ch-1/deliveries', () => (rows instanceof Response ? rows : HttpResponse.json(rows))),
  );
}

function renderLog(over: Partial<Parameters<typeof DeliveryLog>[0]> = {}) {
  const announce = vi.fn();
  const onClose = vi.fn();
  renderWithProviders(<DeliveryLog channel={CHANNEL} onClose={onClose} canWrite announce={announce} {...over} />);
  return { announce, onClose };
}

describe('DeliveryLog', () => {
  it('is closed without a channel', () => {
    renderLog({ channel: null });
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('teaches how a delivery comes about when the channel has had none', async () => {
    serve([]);
    renderLog();

    expect(await screen.findByRole('dialog', { name: 'Deliveries to on-call' })).toBeInTheDocument();
    expect(await screen.findByText(/Nothing has been sent to this channel yet/)).toBeInTheDocument();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('says why the log could not be loaded', async () => {
    serve(HttpResponse.json({ title: 'Down', detail: 'channel store unavailable' }, { status: 503 }));
    renderLog();

    expect(
      await screen.findByText(/The delivery log could not be loaded: channel store unavailable/),
    ).toBeInTheDocument();
  });

  it('states each delivery in words: when it was sent, when the next attempt is due, and that it gave up', async () => {
    serve([
      delivery(1, { state: 'SENT', lastError: 'HTTP 502 on the first attempt', attempts: 2 }),
      delivery(2, { state: 'PENDING', lastError: 'HTTP 500', attempts: 1, deliveredAt: null }),
      delivery(3, { state: 'DEAD', lastError: 'HTTP 404', attempts: 5, deliveredAt: null }),
      delivery(4, { state: 'CANCELLED', deliveredAt: null }),
    ]);
    renderLog();

    const sent = await screen.findByRole('row', { name: /backlog 1/ });
    expect(sent).toHaveTextContent('sent');
    expect(sent).toHaveTextContent(absoluteLabel('2026-09-11T10:01:00Z'));
    // An error on a delivery that was sent is history, not a problem.
    expect(within(sent).getByText(/Earlier attempt:/)).toHaveTextContent('HTTP 502 on the first attempt');
    expect(within(sent).getByText('2', { selector: 'td' })).toBeInTheDocument();

    const pending = screen.getByRole('row', { name: /backlog 2/ });
    expect(pending).toHaveTextContent('waiting');
    expect(pending).toHaveTextContent(`next ${absoluteLabel('2026-09-11T10:05:00Z')}`);
    expect(within(pending).getByText(/Last error:/)).toHaveTextContent('HTTP 500');

    const dead = screen.getByRole('row', { name: /backlog 3/ });
    expect(dead).toHaveTextContent('failed');
    expect(dead).toHaveTextContent('gave up');
    expect(within(dead).getByText(/Last error:/)).toHaveTextContent('HTTP 404');

    // A state the log has no words for is shown as the server named it, lower-cased.
    expect(screen.getByRole('row', { name: /backlog 4/ })).toHaveTextContent('cancelled');
    // Only a failed delivery can be retried.
    expect(screen.getAllByRole('button', { name: /Retry delivery/ })).toHaveLength(1);
  });

  it('puts a failed delivery back on the queue and announces it', async () => {
    serve([delivery(3, { state: 'DEAD', lastError: 'HTTP 404', attempts: 5 })]);
    server.use(
      http.post('*/api/v1/channels/ch-1/deliveries/3/retry', () =>
        HttpResponse.json(delivery(3, { state: 'PENDING' })),
      ),
    );
    const user = userEvent.setup();
    const { announce } = renderLog();

    await user.click(await screen.findByRole('button', { name: 'Retry delivery 3' }));
    await waitFor(() => expect(announce).toHaveBeenCalledWith('Delivery 3 queued again.'));
  });

  it('announces why a retry was refused', async () => {
    serve([delivery(3, { state: 'DEAD', lastError: 'HTTP 404', attempts: 5 })]);
    server.use(
      http.post('*/api/v1/channels/ch-1/deliveries/3/retry', () =>
        HttpResponse.json({ title: 'Conflict', detail: 'The channel is disabled.' }, { status: 409 }),
      ),
    );
    const user = userEvent.setup();
    const { announce } = renderLog();

    await user.click(await screen.findByRole('button', { name: 'Retry delivery 3' }));
    await waitFor(() => expect(announce).toHaveBeenCalledWith('Delivery 3 was not queued: The channel is disabled.'));
  });

  it('keeps retry visible but disabled, with the reason, without alert:write', async () => {
    serve([delivery(3, { state: 'DEAD', lastError: 'HTTP 404', attempts: 5 })]);
    renderLog({ canWrite: false });

    const retry = await screen.findByRole('button', { name: 'Retry delivery 3' });
    expect(retry).toBeDisabled();
    expect(retry).toHaveAttribute('title', 'Retrying needs alert:write');
  });

  it('closes from the drawer', async () => {
    serve([]);
    const user = userEvent.setup();
    const { onClose } = renderLog();

    await screen.findByText(/Nothing has been sent/);
    await user.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalled();
  });
});
