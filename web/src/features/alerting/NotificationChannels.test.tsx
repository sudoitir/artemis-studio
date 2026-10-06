import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { Notifications, notifications } from '@mantine/notifications';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { NotificationChannels } from './NotificationChannels.tsx';
import { paged } from '../../kernel/api/paging.ts';

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

function renderChannels() {
  return renderWithProviders(
    <>
      <Notifications />
      <NotificationChannels />
    </>,
  );
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

afterEach(() => act(() => notifications.clean()));

describe('NotificationChannels', () => {
  beforeEach(() => mockMe(['alert:read', 'alert:write']));

  it('keeps write controls visible but disabled, with the reason, for a reader', async () => {
    mockMe(['alert:read']);
    server.use(http.get('*/api/v1/channels', () => HttpResponse.json(paged([channel()]))));
    renderChannels();

    await screen.findByText('ops-slack');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Add channel' })).toBeDisabled());
    expect(screen.getByRole('button', { name: 'Delete ops-slack' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Delivery log of ops-slack' })).toBeEnabled();
    // The reason is on the page, where a keyboard user can read it, not in a hover.
    expect(screen.getByText('Changing channels needs the alert:write permission.')).toBeInTheDocument();
  });

  it('lists channels without ever rendering the secret', async () => {
    server.use(http.get('*/api/v1/channels', () => HttpResponse.json(paged([channel()]))));
    renderChannels();

    expect(await screen.findByText('ops-slack')).toBeInTheDocument();
    expect(screen.getByText('Slack')).toBeInTheDocument();
    expect(screen.getByText('never used')).toBeInTheDocument();
    expect(screen.queryByDisplayValue(/hooks\.slack\.com/)).not.toBeInTheDocument();
  });

  it('states a failing channel’s last error in the list', async () => {
    server.use(
      http.get('*/api/v1/channels', () =>
        HttpResponse.json(
          paged([
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
      ),
    );
    renderChannels();

    expect(await screen.findByText(/failed \d+s ago/)).toBeInTheDocument();
    expect(screen.getByText('Teams responded 404 NOT_FOUND')).toBeInTheDocument();
    expect(screen.getByText('24h: 5 sent, 2 failed')).toBeInTheDocument();
  });

  it('shows an empty state with no channels', async () => {
    server.use(http.get('*/api/v1/channels', () => HttpResponse.json(paged([]))));
    renderChannels();

    expect(await screen.findByText(/No notification channels configured/)).toBeInTheDocument();
  });

  it('creates a Slack channel from the editor', async () => {
    let created = false;
    server.use(
      http.get('*/api/v1/channels', () => HttpResponse.json(paged(created ? [channel()] : []))),
      http.post('*/api/v1/channels', () => {
        created = true;
        return HttpResponse.json(channel(), { status: 201 });
      }),
    );
    const user = userEvent.setup();
    renderChannels();

    await screen.findByText(/No notification channels configured/);
    await user.click(screen.getByRole('button', { name: 'Add channel' }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText(/^Name/), 'ops-slack');
    await user.type(within(dialog).getByLabelText(/^Webhook URL/), 'https://hooks.slack.com/services/x');
    await user.click(within(dialog).getByRole('button', { name: 'Add channel' }));

    expect(await screen.findByText('ops-slack')).toBeInTheDocument();
    expect(await screen.findByText('Added channel "ops-slack"')).toBeInTheDocument();
  });

  it('validates an email channel on blur and focuses the first invalid field on save', async () => {
    const user = userEvent.setup();
    server.use(http.get('*/api/v1/channels', () => HttpResponse.json(paged([]))));
    renderChannels();

    await screen.findByText(/No notification channels configured/);
    await user.click(screen.getByRole('button', { name: 'Add channel' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('combobox', { name: /^Kind/ }));
    await user.click(await screen.findByRole('option', { name: 'Email (SMTP)' }));

    const to = within(dialog).getByLabelText(/^Recipients/);
    await user.type(to, 'oncall@example.com, not-an-address');
    await user.tab();
    expect(await within(dialog).findByText('"not-an-address" is not a valid address.')).toBeInTheDocument();

    await user.click(within(dialog).getByRole('button', { name: 'Add channel' }));
    await waitFor(() => expect(within(dialog).getByLabelText(/^Name/)).toHaveFocus());
  });

  it('tests an unsaved configuration and states the cause and the next action', async () => {
    server.use(
      http.get('*/api/v1/channels', () => HttpResponse.json(paged([]))),
      http.post('*/api/v1/channels/test', () =>
        HttpResponse.json({
          delivered: false,
          permanent: true,
          error: 'Teams responded 404 NOT_FOUND',
          durationMs: 40,
        }),
      ),
    );
    const user = userEvent.setup();
    renderChannels();

    await screen.findByText(/No notification channels configured/);
    await user.click(screen.getByRole('button', { name: 'Add channel' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('combobox', { name: /^Kind/ }));
    await user.click(await screen.findByRole('option', { name: 'Microsoft Teams' }));
    await user.type(within(dialog).getByLabelText(/^Workflow webhook URL/), 'https://example.logic.azure.com/x');
    await user.click(within(dialog).getByRole('button', { name: 'Send a test' }));

    expect(await within(dialog).findByText('Test not delivered')).toBeInTheDocument();
    expect(within(dialog).getByText('Teams responded 404 NOT_FOUND')).toBeInTheDocument();
    expect(within(dialog).getByText(/Retrying will not help/)).toBeInTheDocument();
  });

  it('states the blast radius before a delete can be armed, then needs the name typed', async () => {
    let deleted = false;
    server.use(
      http.get('*/api/v1/channels', () => HttpResponse.json(paged(deleted ? [] : [channel()]))),
      http.delete('*/api/v1/channels/ch1', () => {
        deleted = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    renderChannels();

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
    expect(await screen.findByText('Deleted channel "ops-slack"')).toBeInTheDocument();
  });
});

describe('NotificationChannels: the list and its row actions', () => {
  beforeEach(() => mockMe(['alert:read', 'alert:write']));

  const health = (over: Record<string, unknown> = {}) => ({
    lastState: 'SENT',
    lastCreatedAt: new Date().toISOString(),
    lastDeliveredAt: new Date().toISOString(),
    lastError: null,
    pending: 0,
    failedLast24h: 0,
    sentLast24h: 4,
    ...over,
  });

  it('says why the channels could not be loaded', async () => {
    server.use(
      http.get('*/api/v1/channels', () =>
        HttpResponse.json({ title: 'Unavailable', detail: 'The database is down' }, { status: 503 }),
      ),
    );
    renderChannels();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The database is down');
    expect(within(alert).getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('shows where each kind delivers, and what a channel without a secret still needs', async () => {
    server.use(
      http.get('*/api/v1/channels', () =>
        HttpResponse.json(
          paged([
            channel({ id: 'a', name: 'hook', kind: 'WEBHOOK', config: '{"url":"https://alerts.example.com/h"}' }),
            channel({
              id: 'b',
              name: 'mail',
              kind: 'EMAIL',
              config: '{"host":"smtp.x.test","to":["a@x.test"]}',
              hasSecret: false,
            }),
            channel({ id: 'c', name: 'teams', kind: 'TEAMS', hasSecret: false, enabled: false }),
          ]),
        ),
      ),
    );
    renderChannels();

    expect(await screen.findByText('alerts.example.com')).toBeInTheDocument();
    expect(screen.getByText('a@x.test via smtp.x.test · no password')).toBeInTheDocument();
    expect(screen.getByText('webhook URL stored as a secret · secret not set')).toBeInTheDocument();
    expect(screen.getAllByText('enabled')).toHaveLength(2);
    expect(screen.getByText('disabled')).toBeInTheDocument();
  });

  it('shows a healthy channel’s last delivery in plain colour and counts what is waiting', async () => {
    server.use(
      http.get('*/api/v1/channels', () => HttpResponse.json(paged([channel({ health: health({ pending: 2 }) })]))),
    );
    renderChannels();

    expect(await screen.findByText(/^sent \d+s ago$/)).toBeInTheDocument();
    expect(screen.getByText('24h: 4 sent, 0 failed, 2 waiting')).toBeInTheDocument();
  });

  it('does not repeat an error for a channel whose last delivery did not die', async () => {
    server.use(
      http.get('*/api/v1/channels', () =>
        HttpResponse.json(paged([channel({ health: health({ lastState: 'PENDING', lastError: 'earlier hiccup' }) })])),
      ),
    );
    renderChannels();

    expect(await screen.findByText(/^waiting \d+s ago$/)).toBeInTheDocument();
    expect(screen.queryByText('earlier hiccup')).not.toBeInTheDocument();
  });

  it('tests a saved channel and announces and shows the outcome', async () => {
    server.use(
      http.get('*/api/v1/channels', () => HttpResponse.json(paged([channel()]))),
      http.post('*/api/v1/channels/ch1/test', () =>
        HttpResponse.json({ delivered: true, permanent: false, durationMs: 31 }),
      ),
    );
    const user = userEvent.setup();
    renderChannels();

    await user.click(await screen.findByRole('button', { name: 'Send test notification to ops-slack' }));

    expect(await screen.findByText('Test of ops-slack')).toBeInTheDocument();
    expect(screen.getByText('Test delivered in 31 ms')).toBeInTheDocument();
    // The result sits in a live region that exists before it, so it is announced when it lands.
    expect(screen.getByRole('status')).toHaveTextContent('Test delivered in 31 ms');
  });

  it('states a test that was not delivered, with its reason and what to do, and one with none', async () => {
    let reply: Record<string, unknown> = { delivered: false, permanent: true, durationMs: 3, error: 'HTTP 404' };
    server.use(
      http.get('*/api/v1/channels', () => HttpResponse.json(paged([channel()]))),
      http.post('*/api/v1/channels/ch1/test', () => HttpResponse.json(reply)),
    );
    const user = userEvent.setup();
    renderChannels();

    const send = await screen.findByRole('button', { name: 'Send test notification to ops-slack' });
    await user.click(send);
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Test not delivered'));
    expect(screen.getByRole('status')).toHaveTextContent('HTTP 404');
    expect(screen.getByRole('status')).toHaveTextContent('Retrying will not help');

    reply = { delivered: false, permanent: false, durationMs: 3 };
    await user.click(send);
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('The receiver gave no reason.'));
    expect(screen.getByRole('status')).toHaveTextContent('This may be temporary');
  });

  it('announces a test that could not be sent at all', async () => {
    server.use(
      http.get('*/api/v1/channels', () => HttpResponse.json(paged([channel()]))),
      http.post('*/api/v1/channels/ch1/test', () =>
        HttpResponse.json({ title: 'Error', detail: 'boom' }, { status: 500 }),
      ),
    );
    const user = userEvent.setup();
    renderChannels();

    await user.click(await screen.findByRole('button', { name: 'Send test notification to ops-slack' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not send a test notification to ops-slack');
    expect(alert).toHaveTextContent('boom Nothing was sent. Try again.');
  });

  it('opens the editor on a channel, and closes it without changing anything', async () => {
    server.use(http.get('*/api/v1/channels', () => HttpResponse.json(paged([channel()]))));
    const user = userEvent.setup();
    renderChannels();

    await user.click(await screen.findByRole('button', { name: 'Edit ops-slack' }));
    const dialog = await screen.findByRole('dialog', { name: 'Edit ops-slack' });
    expect(within(dialog).getByLabelText(/^Name/)).toHaveValue('ops-slack');

    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('opens the delivery log of a channel', async () => {
    server.use(
      http.get('*/api/v1/channels', () => HttpResponse.json(paged([channel()]))),
      http.get('*/api/v1/channels/ch1/deliveries', () => HttpResponse.json(paged([]))),
    );
    const user = userEvent.setup();
    renderChannels();

    await user.click(await screen.findByRole('button', { name: 'Delivery log of ops-slack' }));

    expect(await screen.findByRole('dialog', { name: 'Deliveries to ops-slack' })).toBeInTheDocument();
  });

  it('says nothing stops delivering when no rule routes to the channel, and singular for one rule', async () => {
    server.use(
      http.get('*/api/v1/channels', () =>
        HttpResponse.json(
          paged([
            channel({ id: 'a', name: 'idle', boundRuleCount: 0 }),
            channel({ id: 'b', name: 'busy', boundRuleCount: 1 }),
          ]),
        ),
      ),
    );
    const user = userEvent.setup();
    renderChannels();

    await user.click(await screen.findByRole('button', { name: 'Delete idle' }));
    expect(
      await screen.findByText(/No rule routes to this channel, so no alert stops being delivered/),
    ).toBeInTheDocument();
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: 'Delete busy' }));
    expect(await screen.findByText(/1 rule routes to this channel/)).toBeInTheDocument();
  });

  it('keeps the confirmation open and says why and what next when the delete fails', async () => {
    server.use(
      http.get('*/api/v1/channels', () => HttpResponse.json(paged([channel()]))),
      http.delete('*/api/v1/channels/ch1', () =>
        HttpResponse.json({ title: 'Locked', detail: 'The channel is in use.' }, { status: 409 }),
      ),
    );
    const user = userEvent.setup();
    renderChannels();

    await user.click(await screen.findByRole('button', { name: 'Delete ops-slack' }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText('Type "ops-slack" to confirm'), 'ops-slack');
    await user.click(within(dialog).getByRole('button', { name: 'Delete channel' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not delete channel "ops-slack"');
    expect(alert).toHaveTextContent('The channel is in use. It is still configured. Try again.');
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });
});
