import { beforeEach, describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { NotificationChannels } from './NotificationChannels.tsx';

function channel(over: Record<string, unknown> = {}) {
  return {
    id: 'ch1',
    name: 'ops-slack',
    kind: 'SLACK',
    config: '{}',
    enabled: true,
    hasSecret: true,
    boundRuleCount: 3,
    health: null,
    ...over,
  };
}

function mockMe(permissions: string[]) {
  server.use(
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        id: 'u1',
        username: 'ops',
        mustChangePassword: false,
        grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions }],
      }),
    ),
  );
}

describe('NotificationChannels', () => {
  beforeEach(() => mockMe(['alert:read', 'alert:write']));

  it('keeps write controls visible but disabled, with the reason, for a reader', async () => {
    mockMe(['alert:read']);
    server.use(http.get('*/api/v1/channels', () => HttpResponse.json([channel()])));
    renderWithProviders(<NotificationChannels />);

    await screen.findByText('ops-slack');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Add channel' })).toBeDisabled());
    expect(screen.getByRole('button', { name: 'Delete ops-slack' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Delivery log of ops-slack' })).toBeEnabled();
  });

  it('lists channels without ever rendering the secret', async () => {
    server.use(http.get('*/api/v1/channels', () => HttpResponse.json([channel()])));
    renderWithProviders(<NotificationChannels />);

    expect(await screen.findByText('ops-slack')).toBeInTheDocument();
    expect(screen.getByText('Slack')).toBeInTheDocument();
    expect(screen.getByText('never used')).toBeInTheDocument();
    expect(screen.queryByDisplayValue(/hooks\.slack\.com/)).not.toBeInTheDocument();
  });

  it('states a failing channel’s last error in the list', async () => {
    server.use(
      http.get('*/api/v1/channels', () =>
        HttpResponse.json([
          channel({
            health: {
              lastState: 'DEAD',
              lastCreatedAt: new Date().toISOString(),
              lastDeliveredAt: null,
              lastError: 'Teams responded 404 NOT_FOUND',
              pending: 0,
              failedLast24h: 2,
              sentLast24h: 5,
            },
          }),
        ]),
      ),
    );
    renderWithProviders(<NotificationChannels />);

    expect(await screen.findByText(/failed \d+s ago/)).toBeInTheDocument();
    expect(screen.getByText('Teams responded 404 NOT_FOUND')).toBeInTheDocument();
    expect(screen.getByText('24h: 5 sent, 2 failed')).toBeInTheDocument();
  });

  it('shows an empty state with no channels', async () => {
    server.use(http.get('*/api/v1/channels', () => HttpResponse.json([])));
    renderWithProviders(<NotificationChannels />);

    expect(await screen.findByText(/No notification channels configured/)).toBeInTheDocument();
  });

  it('creates a Slack channel from the editor', async () => {
    let created = false;
    server.use(
      http.get('*/api/v1/channels', () => HttpResponse.json(created ? [channel()] : [])),
      http.post('*/api/v1/channels', () => {
        created = true;
        return HttpResponse.json(channel(), { status: 201 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<NotificationChannels />);

    await screen.findByText(/No notification channels configured/);
    await user.click(screen.getByRole('button', { name: 'Add channel' }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText(/^Name/), 'ops-slack');
    await user.type(within(dialog).getByLabelText(/^Webhook URL/), 'https://hooks.slack.com/services/x');
    await user.click(within(dialog).getByRole('button', { name: 'Add channel' }));

    expect(await screen.findByText('ops-slack')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Added "ops-slack".');
  });

  it('validates an email channel on blur and focuses the first invalid field on save', async () => {
    const user = userEvent.setup();
    server.use(http.get('*/api/v1/channels', () => HttpResponse.json([])));
    renderWithProviders(<NotificationChannels />);

    await screen.findByText(/No notification channels configured/);
    await user.click(screen.getByRole('button', { name: 'Add channel' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('combobox', { name: /^Kind/ }));
    await user.click(await screen.findByRole('option', { name: 'Email (SMTP)', hidden: true }));

    const to = within(dialog).getByLabelText(/^Recipients/);
    await user.type(to, 'oncall@example.com, not-an-address');
    await user.tab();
    expect(await within(dialog).findByText('"not-an-address" is not a valid address.')).toBeInTheDocument();

    await user.click(within(dialog).getByRole('button', { name: 'Add channel' }));
    await waitFor(() => expect(within(dialog).getByLabelText(/^Name/)).toHaveFocus());
  });

  it('tests an unsaved configuration and states the cause and the next action', async () => {
    server.use(
      http.get('*/api/v1/channels', () => HttpResponse.json([])),
      http.post('*/api/v1/channels/test', () =>
        HttpResponse.json({ delivered: false, permanent: true, error: 'Teams responded 404 NOT_FOUND', durationMs: 40 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<NotificationChannels />);

    await screen.findByText(/No notification channels configured/);
    await user.click(screen.getByRole('button', { name: 'Add channel' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('combobox', { name: /^Kind/ }));
    await user.click(await screen.findByRole('option', { name: 'Microsoft Teams', hidden: true }));
    await user.type(within(dialog).getByLabelText(/^Workflow webhook URL/), 'https://example.logic.azure.com/x');
    await user.click(within(dialog).getByRole('button', { name: 'Send a test' }));

    expect(await within(dialog).findByText('Test not delivered')).toBeInTheDocument();
    expect(within(dialog).getByText('Teams responded 404 NOT_FOUND')).toBeInTheDocument();
    expect(within(dialog).getByText(/Retrying will not help/)).toBeInTheDocument();
  });

  it('states the blast radius before a delete can be armed, then needs the name typed', async () => {
    let deleted = false;
    server.use(
      http.get('*/api/v1/channels', () => HttpResponse.json(deleted ? [] : [channel()])),
      http.delete('*/api/v1/channels/ch1', () => {
        deleted = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<NotificationChannels />);

    const trigger = await screen.findByRole('button', { name: 'Delete ops-slack' });
    await user.click(trigger);
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/3 rules route to this channel/)).toBeInTheDocument();
    const confirm = within(dialog).getByRole('button', { name: 'Delete channel' });
    expect(confirm).toBeDisabled();

    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(trigger).toHaveFocus());

    await user.click(trigger);
    const again = await screen.findByRole('dialog');
    await user.type(within(again).getByLabelText('Type "ops-slack" to confirm'), 'ops-slack');
    await user.click(within(again).getByRole('button', { name: 'Delete channel' }));

    expect(await screen.findByText(/No notification channels configured/)).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Deleted "ops-slack".');
  });
});
