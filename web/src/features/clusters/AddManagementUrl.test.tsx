import { afterEach, describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { Notifications, notifications } from '@mantine/notifications';
import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { NodeEndpointView } from './api.ts';
import { AddManagementUrl } from './AddManagementUrl.tsx';

const ENDPOINT = { id: 'n1', name: 'broker-2', coreUrl: 'tcp://broker-2:61617' } as NodeEndpointView;

function open() {
  let closed = 0;
  const user = userEvent.setup();
  renderWithProviders(
    <>
      <Notifications />
      <AddManagementUrl clusterId="c1" endpoint={ENDPOINT} opened onClose={() => (closed += 1)} />
    </>,
  );
  return { user, closed: () => closed };
}

afterEach(() => act(() => notifications.clean()));

describe('AddManagementUrl', () => {
  it('keeps Save live, asks for a URL beside the field, focuses it and sends nothing when pressed empty', async () => {
    let sent = 0;
    server.use(
      http.patch('*/api/v1/clusters/c1/nodes/n1', () => {
        sent += 1;
        return HttpResponse.json(ENDPOINT);
      }),
    );
    const { user } = open();

    const save = await screen.findByRole('button', { name: 'Save' });
    expect(save).toBeEnabled();
    await user.click(save);

    expect(await screen.findByText('Enter a management URL, a Core URL, or both.')).toBeInTheDocument();
    expect(screen.getByLabelText('Management URL')).toHaveFocus();
    expect(sent).toBe(0);
  });

  it('saves the URL, announces it by node and closes', async () => {
    let body: unknown = null;
    server.use(
      http.patch('*/api/v1/clusters/c1/nodes/n1', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(ENDPOINT);
      }),
    );
    const { user, closed } = open();

    await user.type(await screen.findByLabelText('Management URL'), 'http://broker-2:8261/console/jolokia');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(body).toEqual({ jolokiaUrl: 'http://broker-2:8261/console/jolokia' }));
    expect(await screen.findByText('Updated the URL of node tcp://broker-2:61617')).toBeInTheDocument();
    expect(closed()).toBe(1);
  });

  it('states why the broker refused the URL and stays open', async () => {
    server.use(
      http.patch('*/api/v1/clusters/c1/nodes/n1', () =>
        HttpResponse.json({ title: 'Unprocessable', detail: 'Not a Jolokia endpoint.' }, { status: 422 }),
      ),
    );
    const { user, closed } = open();

    await user.type(await screen.findByLabelText('Management URL'), 'http://nowhere');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Not a Jolokia endpoint.');
    expect(closed()).toBe(0);
  });
});
