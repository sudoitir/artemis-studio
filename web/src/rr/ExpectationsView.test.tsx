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
    replyAddress: 'orders.reply',
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
});
