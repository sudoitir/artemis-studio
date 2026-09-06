import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../test/render.tsx';
import { server } from '../test/setup.ts';
import { AddressPicker } from './AddressPicker.tsx';

function queue(over: Record<string, unknown> = {}) {
  return {
    address: 'orders.request',
    queueName: 'orders.request',
    routingType: 'ANYCAST',
    durable: true,
    totalMessageCount: 12,
    totalConsumerCount: 1,
    totalDeliveringCount: 0,
    totalScheduledCount: 0,
    nodesPresent: 3,
    nodesTotal: 3,
    perNode: [],
    ...over,
  };
}

function page(rows: ReturnType<typeof queue>[]) {
  return { data: rows, count: rows.length, page: 1, pageSize: 300 };
}

function Harness({ initial = '' }: { initial?: string }) {
  return <AddressPicker clusterId="c1" value={initial} onChange={() => {}} label="Request address" />;
}

/**
 * Options are queried with `hidden: true`. Mantine positions the dropdown with
 * floating-ui, which needs real layout; under jsdom the popover wrapper keeps
 * `display: none`, so Testing Library treats everything inside it as
 * inaccessible. That is an artifact of the environment, not of the markup — the
 * dropdown's real visibility and keyboard behaviour are checked in a browser.
 * The queries still go by role and accessible name, so a genuinely unnamed
 * option still fails here.
 *
 * For the same reason the dropdown stays mounted whether open or closed, so
 * open/close and focus-containment behaviour is not asserted here — it is
 * checked in a browser. What is asserted is everything that is observable:
 * which options are offered, how they are named, the routing-type filter, the
 * unknown-address hint, and the failure path.
 */
const opt = { hidden: true } as const;

describe('AddressPicker', () => {
  it('suggests the broker’s addresses with their type and depth', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/queues', () =>
        HttpResponse.json(page([queue(), queue({ address: 'orders.events', routingType: 'MULTICAST' })])),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    await user.click(screen.getByRole('textbox', { name: /request address/i }));

    // The whole row is one accessible name, so a screen reader announces the
    // address with the two facts that distinguish a request queue from a reply one.
    expect(
      await screen.findByRole(
        'option',
        { name: 'orders.request, anycast, 12 messages, on 3 of 3 nodes', ...opt },
        { timeout: 4000 },
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('option', { name: /^orders\.events, multicast,/, ...opt }),
    ).toBeInTheDocument();
  });

  it('shows an address in full rather than truncating it to fit the field', async () => {
    // The option used to lay the address and its meta out as two columns inside a
    // 240px dropdown, so `flex: none` meta won and every long address rendered as
    // an ellipsis — useless for exactly the names this picker exists to tell apart.
    const long = 'orders.reply.responder-with-a-rather-long-node-name.v1';
    server.use(
      http.get('*/api/v1/clusters/c1/queues', () =>
        HttpResponse.json(page([queue({ address: long })])),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    await user.click(screen.getByRole('textbox', { name: /request address/i }));

    const option = await screen.findByRole('option', { name: new RegExp(`^${long},`), ...opt }, { timeout: 4000 });
    const name = option.querySelector(`[title="${long}"]`);
    expect(name).not.toBeNull();
    expect(name).toHaveTextContent(long);
  });

  it('filters the suggestions by routing type', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/queues', () =>
        HttpResponse.json(page([queue(), queue({ address: 'orders.events', routingType: 'MULTICAST' })])),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    await user.click(screen.getByRole('textbox', { name: /request address/i }));
    await screen.findByRole('option', { name: /orders\.events/, ...opt }, { timeout: 4000 });

    await user.click(screen.getByRole('checkbox', { name: 'multicast', ...opt }));

    await waitFor(() =>
      expect(screen.queryByRole('option', { name: /orders\.request/, ...opt })).not.toBeInTheDocument(),
    );
    expect(screen.getByRole('option', { name: /orders\.events/, ...opt })).toBeInTheDocument();
  });

  it('says so when the typed name matches nothing, without blocking it', async () => {
    server.use(http.get('*/api/v1/clusters/c1/queues', () => HttpResponse.json(page([]))));
    renderWithProviders(
      <AddressPicker
        clusterId="c1"
        value="not.a.real.address"
        onChange={() => {}}
        label="Request address"
        unknownHint="No address on this cluster has that name yet."
      />,
    );

    expect(
      await screen.findByText('No address on this cluster has that name yet.'),
    ).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: /request address/i })).toHaveValue(
      'not.a.real.address',
    );
  });

  it('stays usable when the queue list cannot be read', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/queues', () =>
        HttpResponse.json(
          { title: 'Unexpected broker response', detail: 'The broker is unreachable.' },
          { status: 502 },
        ),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    await user.click(screen.getByRole('textbox', { name: /request address/i }));

    expect(await screen.findByText(/type an address by hand/i)).toBeInTheDocument();
  });
});
