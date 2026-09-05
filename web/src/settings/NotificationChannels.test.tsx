import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../test/render.tsx';
import { server } from '../test/setup.ts';
import { NotificationChannels } from './NotificationChannels.tsx';

function channel(over: Record<string, unknown> = {}) {
  return {
    id: 'ch1',
    name: 'ops-slack',
    kind: 'SLACK',
    config: '{}',
    enabled: true,
    hasSecret: true,
    ...over,
  };
}

describe('NotificationChannels', () => {
  it('lists channels without ever rendering the secret', async () => {
    server.use(http.get('*/api/v1/channels', () => HttpResponse.json([channel()])));
    renderWithProviders(<NotificationChannels />);

    expect(await screen.findByText('ops-slack')).toBeInTheDocument();
    expect(screen.getByText('configured')).toBeInTheDocument();
    expect(screen.queryByDisplayValue(/hooks\.slack\.com/)).not.toBeInTheDocument();
  });

  it('shows an empty state with no channels', async () => {
    server.use(http.get('*/api/v1/channels', () => HttpResponse.json([])));
    renderWithProviders(<NotificationChannels />);

    expect(await screen.findByText(/No notification channels configured/)).toBeInTheDocument();
  });

  it('creates a Slack channel from the form', async () => {
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
    await user.type(screen.getByLabelText('Name'), 'ops-slack');
    await user.type(screen.getByLabelText('Webhook URL'), 'https://hooks.slack.com/services/x');
    await user.click(screen.getByRole('button', { name: 'Add channel' }));

    expect(await screen.findByText('ops-slack')).toBeInTheDocument();
  });
});
