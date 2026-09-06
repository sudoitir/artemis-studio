import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../test/render.tsx';
import { server } from '../test/setup.ts';
import { ExpectationsView } from './ExpectationsView.tsx';

function expectation(over: Record<string, unknown> = {}) {
  return {
    id: 'e1',
    requestAddress: 'orders.request',
    replyAddresses: ['orders.reply'],
    resolvedReplyAddresses: ['orders.reply'],
    replyAddressesCapped: false,
    correlationProperty: null,
    deadlineMs: 30_000,
    samplePerMin: 10,
    capturePayload: false,
    enabled: true,
    ...over,
  };
}

describe('ExpectationsView', () => {
  it('lists declared expectations', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/rr/expectations', () => HttpResponse.json([expectation()])),
    );
    renderWithProviders(<ExpectationsView clusterId="c1" />);

    expect(await screen.findByText('orders.request')).toBeInTheDocument();
  });

  it('shows an empty state with no expectations', async () => {
    server.use(http.get('*/api/v1/clusters/c1/rr/expectations', () => HttpResponse.json([])));
    renderWithProviders(<ExpectationsView clusterId="c1" />);

    expect(await screen.findByText(/No addresses declared yet/)).toBeInTheDocument();
  });

  it('creates a new expectation from the form', async () => {
    let created = false;
    server.use(
      http.get('*/api/v1/clusters/c1/rr/expectations', () =>
        HttpResponse.json(created ? [expectation()] : []),
      ),
      http.post('*/api/v1/clusters/c1/rr/expectations', () => {
        created = true;
        return HttpResponse.json(expectation(), { status: 201 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<ExpectationsView clusterId="c1" />);

    await screen.findByText(/No addresses declared yet/);
    await user.type(screen.getByLabelText('Request address'), 'orders.request');
    await user.click(screen.getByRole('button', { name: 'Add' }));

    expect(await screen.findByText('orders.request')).toBeInTheDocument();
  });

  it('sends every reply address pattern the operator entered', async () => {
    const sent: { replyAddresses?: string[] }[] = [];
    let created = false;
    server.use(
      http.get('*/api/v1/clusters/c1/queues', () =>
        HttpResponse.json({ data: [], page: 1, size: 300, total: 0 }),
      ),
      http.get('*/api/v1/clusters/c1/rr/expectations', () =>
        HttpResponse.json(created ? [expectation()] : []),
      ),
      http.post('*/api/v1/clusters/c1/rr/expectations', async ({ request }) => {
        sent.push((await request.json()) as { replyAddresses?: string[] });
        created = true;
        return HttpResponse.json(expectation(), { status: 201 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<ExpectationsView clusterId="c1" />);

    await screen.findByText(/No addresses declared yet/);
    await user.type(screen.getByLabelText('Request address'), 'orders.request');

    // The label is associated with three nodes: the option listbox, the hidden
    // input carrying the value, and the visible one that takes typing.
    const replies = screen
      .getAllByLabelText('Reply addresses')
      .find(
        (el): el is HTMLInputElement =>
          el.tagName === 'INPUT' && (el as HTMLInputElement).type !== 'hidden',
      )!;
    await user.type(replies, 'orders.reply.a{enter}');
    await user.type(replies, 'orders.reply.*{enter}');
    await user.click(screen.getByRole('button', { name: 'Add' }));

    await screen.findByText('orders.request');
    expect(sent[0]?.replyAddresses).toEqual(['orders.reply.a', 'orders.reply.*']);
  });

  it('explains what leaving the reply addresses empty means', async () => {
    // Conflating "I meant temporary queues" with "I have not filled this in" is
    // what produced an expectation that could never be joined.
    server.use(http.get('*/api/v1/clusters/c1/rr/expectations', () => HttpResponse.json([])));
    renderWithProviders(<ExpectationsView clusterId="c1" />);

    expect(await screen.findByText(/temporary queue/)).toBeInTheDocument();
  });

  it('says when a declared pattern matches nothing on the cluster yet', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/rr/expectations', () =>
        HttpResponse.json([
          expectation({ replyAddresses: ['orders.reply.*'], resolvedReplyAddresses: [] }),
        ]),
      ),
    );
    renderWithProviders(<ExpectationsView clusterId="c1" />);

    expect(await screen.findByText('no matching queue yet')).toBeInTheDocument();
  });

  it('flags an over-broad pattern rather than silently tracing a subset', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/rr/expectations', () =>
        HttpResponse.json([
          expectation({
            replyAddresses: ['*'],
            resolvedReplyAddresses: Array.from({ length: 32 }, (_, i) => `a.${i}`),
            replyAddressesCapped: true,
          }),
        ]),
      ),
    );
    renderWithProviders(<ExpectationsView clusterId="c1" />);

    expect(await screen.findByText(/too broad/)).toBeInTheDocument();
  });
});
