import { afterEach, describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { Notifications, notifications } from '@mantine/notifications';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { CapabilitiesSection, CredentialsSection } from './ClusterSettings.tsx';

const CLUSTER = '11111111-1111-1111-1111-111111111111';

function cluster() {
  server.use(http.get('*/api/v1/clusters/:id', () => HttpResponse.json({ id: CLUSTER, name: 'prod-eu' })));
}

function rotation() {
  const calls: unknown[] = [];
  server.use(
    http.put('*/api/v1/clusters/:id/credentials', async ({ request }) => {
      calls.push(await request.json());
      return new HttpResponse(null, { status: 204 });
    }),
  );
  return calls;
}

function open() {
  const user = userEvent.setup();
  renderWithProviders(
    <>
      <Notifications />
      <CredentialsSection clusterId={CLUSTER} />
    </>,
  );
  return user;
}

afterEach(() => act(() => notifications.clean()));

describe('CredentialsSection', () => {
  it('holds its place while the cluster loads, and says why it cannot be read', async () => {
    server.use(
      http.get('*/api/v1/clusters/:id', () =>
        HttpResponse.json({ title: 'Error', detail: 'The cluster store is down.' }, { status: 500 }),
      ),
    );
    renderWithProviders(<CredentialsSection clusterId={CLUSTER} />);

    expect(screen.getByText('Loading the cluster')).toBeInTheDocument();
    expect(await screen.findByText('The cluster store is down.')).toBeInTheDocument();
  });

  it('asks for what is missing beside its field and opens no confirmation', async () => {
    cluster();
    const user = open();

    await user.click(await screen.findByRole('button', { name: 'Save management (jolokia) credentials…' }));

    expect(screen.getByText('Enter the account name.')).toBeInTheDocument();
    expect(screen.getByText('Enter the new password.')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('states what it replaces, arms on the cluster name, sends the new account and announces it', async () => {
    cluster();
    const calls = rotation();
    const user = open();

    await user.type(await screen.findByLabelText('Username'), 'artemis');
    await user.type(screen.getByLabelText('Password'), 'secret');
    await user.click(screen.getByRole('button', { name: 'Save management (jolokia) credentials…' }));

    const dialog = await screen.findByRole('dialog', { name: 'Save management (jolokia) credentials' });
    expect(dialog).toHaveTextContent('replaces the stored management (jolokia) account of prod-eu on every node');
    const confirm = within(dialog).getByRole('button', { name: 'Save management (jolokia) credentials' });
    expect(confirm).toBeDisabled();
    await user.type(within(dialog).getByLabelText('Type "prod-eu" to confirm'), 'prod-eu');
    await user.click(confirm);

    await waitFor(() => expect(calls).toEqual([{ username: 'artemis', password: 'secret', kind: 'JOLOKIA_BASIC' }]));
    expect(await screen.findByText('Saved management (jolokia) credentials of prod-eu')).toBeInTheDocument();
  });

  it('states why a refused rotation failed and keeps the confirmation open', async () => {
    cluster();
    server.use(
      http.put('*/api/v1/clusters/:id/credentials', () =>
        HttpResponse.json({ title: 'Unprocessable', detail: 'The broker rejected the account.' }, { status: 422 }),
      ),
    );
    const user = open();

    await user.type(await screen.findByLabelText('Username'), 'artemis');
    await user.type(screen.getByLabelText('Password'), 'secret');
    await user.click(screen.getByRole('button', { name: 'Save management (jolokia) credentials…' }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText('Type "prod-eu" to confirm'), 'prod-eu');
    await user.click(within(dialog).getByRole('button', { name: 'Save management (jolokia) credentials' }));

    expect(await within(dialog).findByText('The broker rejected the account.')).toBeInTheDocument();
  });
});

describe('CapabilitiesSection', () => {
  it('holds its place while the cluster loads', () => {
    server.use(http.get('*/api/v1/clusters/:id', () => new Promise(() => undefined)));
    renderWithProviders(<CapabilitiesSection clusterId={CLUSTER} />);

    expect(screen.getByText('Loading the cluster')).toBeInTheDocument();
  });
});
