import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { SecuritySettings } from './SecuritySettings.tsx';

const ROTATION = {
  id: '00000000-0000-0000-0000-000000000001',
  fromVersion: 1,
  toVersion: 2,
  status: 'SUCCEEDED',
  startedBy: 'ops',
  startedAt: '2026-09-30T10:00:00Z',
  finishedAt: '2026-09-30T10:01:00Z',
  rewrapped: 7,
  remaining: 0,
};

function status(over: Record<string, unknown> = {}) {
  return http.get('*/api/v1/settings/secrets', () =>
    HttpResponse.json({
      provider: 'vault',
      currentVersion: 1,
      availableVersions: [1, 2],
      countsByVersion: { '1': 7 },
      ...over,
    }),
  );
}

function me(authenticatedAt: string, permissions = ['*']) {
  return http.get('*/api/v1/auth/me', () =>
    HttpResponse.json({
      id: 'u1',
      username: 'ops',
      mustChangePassword: false,
      grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions }],
      reauthentication: { method: 'PASSWORD', startPath: null, authenticatedAt, windowSeconds: 300 },
    }),
  );
}

const fresh = () => new Date().toISOString();

describe('SecuritySettings', () => {
  it('renders the provider, versions and the last rotation', async () => {
    server.use(status({ lastRotation: ROTATION }), me(fresh()));
    renderWithProviders(<SecuritySettings />);

    expect(await screen.findByText('HashiCorp Vault')).toBeInTheDocument();
    expect(screen.getByRole('table', { name: 'Key versions' })).toHaveTextContent('7');
    expect(screen.getByText('Newer, available to rotate to')).toBeInTheDocument();
    expect(screen.getByText('Last rotation: Succeeded')).toBeInTheDocument();
    expect(screen.getByText(/7 re-wrapped, 0 remaining/)).toBeInTheDocument();
  });

  it('teaches when no rotation has run, and explains the disabled control', async () => {
    server.use(status({ availableVersions: [1] }), me(fresh()));
    renderWithProviders(<SecuritySettings />);

    expect(await screen.findByText('No rotation has run')).toBeInTheDocument();
    const user = userEvent.setup();
    expect(screen.getByRole('button', { name: 'Rotate key' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Why rotating the key is unavailable' }));
    expect(await screen.findByText('Add a newer key version to the provider first.')).toBeInTheDocument();
  });

  it('explains a missing permission', async () => {
    server.use(status({ lastRotation: ROTATION }), me(fresh(), ['settings:read']));
    renderWithProviders(<SecuritySettings />);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Why rotating the key is unavailable' }));
    expect(await screen.findByText('You need the settings-write permission.')).toBeInTheDocument();
  });

  it('asks for a step-up on 403, then retries and succeeds', async () => {
    let signedIn = new Date(Date.now() - 3_600_000).toISOString();
    let started = 0;
    server.use(
      status({ lastRotation: ROTATION }),
      http.get('*/api/v1/auth/me', () =>
        HttpResponse.json({
          id: 'u1',
          username: 'ops',
          mustChangePassword: false,
          grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
          reauthentication: { method: 'PASSWORD', startPath: null, authenticatedAt: signedIn, windowSeconds: 300 },
        }),
      ),
      http.post('*/api/v1/settings/secrets/rotations', () => {
        if (new Date(signedIn).getTime() < Date.now() - 300_000) {
          return HttpResponse.json(
            {
              type: 'https://studio/problems/reauthentication-required',
              title: 'Reauthentication required',
              status: 403,
            },
            { status: 403, headers: { 'Content-Type': 'application/problem+json' } },
          );
        }
        started++;
        return HttpResponse.json({ ...ROTATION, status: 'RUNNING' }, { status: 202 });
      }),
      http.post('*/api/v1/auth/reauthenticate', () => {
        signedIn = fresh();
        return HttpResponse.json({
          method: 'PASSWORD',
          startPath: null,
          authenticatedAt: signedIn,
          windowSeconds: 300,
        });
      }),
    );
    renderWithProviders(<SecuritySettings />);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Rotate key' }));
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('re-wraps 7 stored secrets from version 1 to version 2');
    await user.click(within(dialog).getByRole('button', { name: 'Rotate key' }));

    await user.type(await screen.findByLabelText('Your password'), 'secret');
    await user.click(screen.getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(started).toBe(1));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('polls a running rotation and shows counting, then progress', async () => {
    let n = 0;
    server.use(
      me(fresh()),
      http.get('*/api/v1/settings/secrets', () =>
        HttpResponse.json({
          provider: 'env',
          currentVersion: 1,
          availableVersions: [1, 2],
          countsByVersion: { '1': 7 },
          lastRotation: {
            ...ROTATION,
            status: 'RUNNING',
            finishedAt: undefined,
            rewrapped: n++ ? 3 : 0,
            remaining: n > 1 ? 4 : 0,
          },
        }),
      ),
    );
    renderWithProviders(<SecuritySettings />);
    expect(await screen.findByText('Progress: counting…')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Rotate key' })).toBeDisabled();
    expect(
      await screen.findByRole('progressbar', { name: 'Rotation progress' }, { timeout: 4_000 }),
    ).toBeInTheDocument();
    expect(await screen.findByText(/3 re-wrapped, 4 remaining/, undefined, { timeout: 4_000 })).toBeInTheDocument();
  });

  it('finishes a partial rotation and says when an old version can go', async () => {
    server.use(
      me(fresh()),
      status({
        availableVersions: [1, 2],
        currentVersion: 2,
        countsByVersion: { '1': 3, '2': 4 },
        lastRotation: ROTATION,
      }),
    );
    renderWithProviders(<SecuritySettings />);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Rotate key' }));
    expect(await screen.findByRole('dialog')).toHaveTextContent(
      'finishes re-wrapping 3 secrets still under older versions',
    );
  });

  it('says a lower version protects nothing after a successful rotation', async () => {
    server.use(me(fresh()), status({ currentVersion: 2, countsByVersion: { '2': 7 }, lastRotation: ROTATION }));
    renderWithProviders(<SecuritySettings />);
    expect(await screen.findByText(/Version 1 protects no secrets and can be removed/)).toBeInTheDocument();
  });

  it('keeps an unused older version until a running rotation succeeds', async () => {
    server.use(
      me(fresh()),
      status({ currentVersion: 2, countsByVersion: { '2': 7 }, lastRotation: { ...ROTATION, status: 'RUNNING' } }),
    );
    renderWithProviders(<SecuritySettings />);
    expect(await screen.findByText('Older, unused: keep until the rotation succeeds')).toBeInTheDocument();
    expect(screen.queryByText(/safe to remove/)).not.toBeInTheDocument();
  });

  it('shows the cause of a failed rotation', async () => {
    server.use(me(fresh()), status({ lastRotation: { ...ROTATION, status: 'FAILED', error: 'Vault is sealed' } }));
    renderWithProviders(<SecuritySettings />);
    expect(await screen.findByText('Last rotation: Failed')).toBeInTheDocument();
    expect(screen.getByText(/Vault is sealed/)).toBeInTheDocument();
  });

  it('states a load failure and offers a retry', async () => {
    server.use(
      http.get('*/api/v1/settings/secrets', () =>
        HttpResponse.json({ title: 'Boom', status: 500, detail: 'db down' }, { status: 500 }),
      ),
    );
    renderWithProviders(<SecuritySettings />);
    expect(await screen.findByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });
});
