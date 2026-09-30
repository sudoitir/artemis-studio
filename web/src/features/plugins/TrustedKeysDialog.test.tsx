import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { TrustedKeysView } from './api.ts';
import { TrustedKeysDialog } from './TrustedKeysDialog.tsx';

const ACME = 'AB:CD:EF';

const me = () =>
  http.get('*/api/v1/auth/me', () =>
    HttpResponse.json({
      id: 'u1',
      username: 'ops',
      mustChangePassword: false,
      grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
      reauthentication: {
        method: 'PASSWORD',
        startPath: null,
        authenticatedAt: new Date().toISOString(),
        windowSeconds: 300,
      },
    }),
  );

describe('Trusted keys', () => {
  it('adds a pasted key and lists it', async () => {
    const view: TrustedKeysView = { keys: [], allowUnverified: false, signedPlugins: {} };
    let body: unknown = null;
    server.use(
      me(),
      http.get('*/api/v1/admin/plugins/keys', () => HttpResponse.json(view)),
      http.post('*/api/v1/admin/plugins/keys', async ({ request }) => {
        body = await request.json();
        view.keys.push({
          fingerprint: ACME,
          name: 'Acme',
          subject: 'CN=Acme',
          addedAt: new Date().toISOString(),
          addedBy: 'ops',
        });
        return HttpResponse.json(view.keys[0], { status: 201 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<TrustedKeysDialog opened onClose={() => undefined} />);

    expect(await screen.findByText(/No key is trusted yet/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Add key' }));
    expect(screen.getByText('Give the key a name, such as the publisher.')).toBeInTheDocument();
    expect(body).toBeNull();

    await user.type(screen.getByLabelText(/Key name/), 'Acme');
    await user.type(screen.getByLabelText(/Certificate or public key/), 'PEM');
    await user.click(screen.getByRole('button', { name: 'Add key' }));

    await waitFor(() => expect(body).toEqual({ name: 'Acme', pem: 'PEM' }));
    expect(await screen.findByText(ACME)).toBeInTheDocument();
    expect(screen.getByText('Trusted Acme.')).toBeInTheDocument();
  });

  it('removes a key after naming the plugins that become unverified, by keyboard alone', async () => {
    const view: TrustedKeysView = {
      keys: [
        { fingerprint: ACME, name: 'Acme', subject: 'CN=Acme', addedAt: new Date().toISOString(), addedBy: 'ops' },
      ],
      allowUnverified: false,
      signedPlugins: { [ACME]: ['acme-notes', 'acme-tools'] },
    };
    let removed: string | null = null;
    server.use(
      me(),
      http.get('*/api/v1/admin/plugins/keys', () => HttpResponse.json(view)),
      http.delete('*/api/v1/admin/plugins/keys/:fingerprint', ({ params }) => {
        removed = params.fingerprint as string;
        view.keys = [];
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<TrustedKeysDialog opened onClose={() => undefined} />);

    const trigger = await screen.findByRole('button', { name: 'Remove Acme' });
    trigger.focus();
    await user.keyboard('{Enter}');

    const dialog = await screen.findByRole('dialog', { name: 'Remove Acme' });
    expect(within(dialog).getByText(/These plugins become unverified: acme-notes, acme-tools/)).toBeInTheDocument();
    await waitFor(() => expect(dialog).toContainElement(document.activeElement as HTMLElement));

    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Remove Acme' })).toBeNull());
    expect(screen.getByRole('dialog', { name: 'Trusted keys' })).toBeInTheDocument();
    await waitFor(() => expect(trigger).toHaveFocus());

    await user.keyboard('{Enter}');
    const again = await screen.findByRole('dialog', { name: 'Remove Acme' });
    await user.click(within(again).getByRole('button', { name: 'Remove Acme' }));
    await waitFor(() => expect(removed).toBe(ACME));
    expect(await screen.findByText('Removed Acme.')).toBeInTheDocument();
  });

  it('turns unverified plugins on with a danger note beside the switch', async () => {
    let allowed: unknown = null;
    server.use(
      me(),
      http.get('*/api/v1/admin/plugins/keys', () =>
        HttpResponse.json({ keys: [], allowUnverified: false, signedPlugins: {} }),
      ),
      http.put('*/api/v1/admin/plugins/trust-policy', async ({ request }) => {
        allowed = await request.json();
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<TrustedKeysDialog opened onClose={() => undefined} />);
    expect(await screen.findByText(/^Danger: an unverified plugin runs code/)).toBeInTheDocument();
    await user.click(screen.getByRole('switch', { name: /^Allow unverified plugins/ }));
    await waitFor(() => expect(allowed).toEqual({ allowUnverified: true }));
  });
});
