import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { FindingsInbox } from './FindingsInbox.tsx';

const FINDING = {
  id: 'f1',
  address: 'orders.eu',
  location: 'PROPERTY',
  fieldPath: 'contact',
  dataClass: 'EMAIL',
  dataClassLabel: 'email',
  status: 'OPEN',
  hitCount: 1200,
  firstSeenAt: '2026-09-14T00:00:00Z',
  lastSeenAt: '2026-09-14T01:00:00Z',
};

function mockApis(permissions: string[], byStatus: Record<string, unknown[]> = { OPEN: [FINDING] }) {
  server.use(
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        id: 'u1',
        username: 'ada',
        mustChangePassword: false,
        grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions }],
      }),
    ),
    http.get('*/api/v1/clusters', () => HttpResponse.json([])),
    http.get('*/api/v1/governance/findings', ({ request }) => {
      const status = new URL(request.url).searchParams.get('status') ?? 'OPEN';
      return HttpResponse.json(byStatus[status] ?? []);
    }),
  );
}

describe('FindingsInbox', () => {
  it('lists an open finding by field, address and class, never a value', async () => {
    mockApis(['governance:read', 'governance:write']);
    renderWithProviders(<FindingsInbox />);

    expect(await screen.findByText('property contact')).toBeInTheDocument();
    expect(screen.getByText('orders.eu')).toBeInTheDocument();
    expect(screen.getByText('1,200')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Confirm property contact on orders.eu as email' })).toBeEnabled();
  });

  it('teaches when the inbox is empty', async () => {
    mockApis(['governance:read'], { OPEN: [] });
    renderWithProviders(<FindingsInbox />);

    expect(await screen.findByText(/No personal data has been detected in a field that no rule covers/)).toBeInTheDocument();
  });

  it('says a filtered-empty view is filtered, and offers to clear the filter', async () => {
    mockApis(['governance:read'], { OPEN: [FINDING], DISMISSED: [], ALL: [FINDING] });
    const user = userEvent.setup();
    renderWithProviders(<FindingsInbox />);

    await screen.findByText('property contact');
    await user.click(screen.getByRole('radio', { name: 'Dismissed' }));
    expect(await screen.findByText(/No dismissed findings/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Show all findings' }));
    expect(await screen.findByText('property contact')).toBeInTheDocument();
  });

  it('keeps decisions visible but disabled, with the reason, for a read-only user', async () => {
    mockApis(['governance:read']);
    renderWithProviders(<FindingsInbox />);

    expect(await screen.findByText(/needs the governance:write permission/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^Dismiss property contact/ })).toBeDisabled();
  });

  it('announces a successful dismissal from the keyboard', async () => {
    mockApis(['governance:write']);
    server.use(
      http.post('*/api/v1/governance/findings/f1/dismiss', () => HttpResponse.json({ ...FINDING, status: 'DISMISSED' })),
    );
    const user = userEvent.setup();
    renderWithProviders(<FindingsInbox />);

    const dismiss = await screen.findByRole('button', { name: /^Dismiss property contact/ });
    dismiss.focus();
    await user.keyboard('{Enter}');

    await waitFor(() =>
      expect(screen.getByRole('status')).toHaveTextContent('Dismissed: property contact on orders.eu is no longer masked'),
    );
  });

  it('states the cause and the next action when a decision fails', async () => {
    mockApis(['governance:write']);
    server.use(
      http.post('*/api/v1/governance/findings/f1/confirm', () =>
        HttpResponse.json(
          { title: 'Conflict', detail: 'This finding was already dismissed.' },
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<FindingsInbox />);

    await user.click(await screen.findByRole('button', { name: /^Confirm property contact/ }));

    expect(await screen.findByText('The decision did not apply')).toBeInTheDocument();
    expect(screen.getByText(/was not confirmed: .* Try again\./)).toBeInTheDocument();
  });
});
