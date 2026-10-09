import { afterEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { stubPasskeys, unstubPasskeys } from '../../test/passkeys.ts';
import { server } from '../../test/setup.ts';
import type { MfaStatusView } from './api.ts';
import { TwoStepSection } from './TwoStepSection.tsx';
import { holdButton } from '../../test/hold.ts';

const FIREFOX = 'Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0';
const ago = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString();
const ahead = (days: number) => new Date(Date.now() + days * 86_400_000).toISOString();

const OFF: MfaStatusView = {
  passwordAccount: true,
  required: false,
  enrolled: false,
  totpEnrolled: false,
  recoveryCodesRemaining: 0,
  webauthn: { available: true, reason: null },
  passkeys: [],
  trustedDevices: [],
};

const ON: MfaStatusView = {
  ...OFF,
  enrolled: true,
  totpEnrolled: true,
  recoveryCodesRemaining: 10,
  passkeys: [{ id: 'pk1', label: 'Work laptop', created: ago(60 * 24 * 3), lastUsed: ago(30) }],
};

const me = (authenticatedAt = new Date().toISOString(), method = 'PASSWORD') =>
  http.get('*/api/v1/auth/me', () =>
    HttpResponse.json({
      id: 'u1',
      username: 'alice',
      mustChangePassword: false,
      secondFactorEnrolmentRequired: false,
      grants: [],
      reauthentication: { method, startPath: null, authenticatedAt, windowSeconds: 300 },
    }),
  );

const serve = (state: { status: MfaStatusView }) =>
  server.use(http.get('*/api/v1/auth/mfa', () => HttpResponse.json(state.status)));

const problem = (slug: string, status: number, detail?: string) =>
  HttpResponse.json({ type: `https://artemis-studio.dev/problems/${slug}`, title: slug, detail }, { status });

afterEach(() => unstubPasskeys());

describe('TwoStepSection', () => {
  it('holds the height of what replaces it while the status loads', () => {
    server.use(
      me(),
      http.get('*/api/v1/auth/mfa', () => new Promise(() => undefined)),
    );
    renderWithProviders(<TwoStepSection />);

    expect(screen.getByText('Loading two-step verification')).toBeInTheDocument();
  });

  it('says it is required in words, not by colour', async () => {
    server.use(me());
    serve({ status: { ...ON, required: true } });
    renderWithProviders(<TwoStepSection />);

    expect(await screen.findByText('Required by your role')).toBeInTheDocument();
  });

  it('says in words that two-step verification is off, and offers to set it up', async () => {
    server.use(me());
    stubPasskeys({});
    serve({ status: OFF });
    renderWithProviders(<TwoStepSection />);

    expect(await screen.findByText('Two-step verification is off')).toBeInTheDocument();
    expect(screen.getByText(/Signing in asks for your password only/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Set up authenticator app' })).toBeEnabled();
    expect(screen.queryByRole('button', { name: 'Remove authenticator app' })).not.toBeInTheDocument();
    expect(screen.getByText(/A passkey signs you in with your fingerprint/)).toBeInTheDocument();
    // Recovery codes need a factor, and the reason is beside the control that waits for one.
    expect(screen.getByRole('button', { name: 'Regenerate' })).toHaveAccessibleDescription(
      'Created when you set up your first method.',
    );
  });

  it('teaches what trusting a device does when there are none', async () => {
    server.use(me());
    serve({ status: ON });
    renderWithProviders(<TwoStepSection />);

    expect(await screen.findByText(/you can trust that browser so it asks for your password only/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Revoke all' })).not.toBeInTheDocument();
  });

  it('shows it is on, that the role requires it, the passkeys, and how many codes are left', async () => {
    server.use(me());
    serve({ status: { ...ON, required: true } });
    renderWithProviders(<TwoStepSection />);

    expect(await screen.findByText('Two-step verification is on')).toBeInTheDocument();
    expect(screen.getByText('Required by your role')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Replace authenticator app' })).toBeInTheDocument();
    const row = within(screen.getByRole('list', { name: 'Passkeys' })).getByRole('listitem');
    expect(within(row).getByText('Work laptop')).toBeInTheDocument();
    expect(within(row).getByText('3d ago')).toBeInTheDocument();
    expect(within(row).getByText('30m ago')).toBeInTheDocument();
    expect(screen.getByText(/10 of 10 left/)).toBeInTheDocument();
  });

  it('warns when few recovery codes are left', async () => {
    server.use(me());
    serve({ status: { ...ON, recoveryCodesRemaining: 2 } });
    renderWithProviders(<TwoStepSection />);

    expect(await screen.findByText(/2 of 10 left/)).toBeInTheDocument();
    expect(screen.getByText(/You are running low\. Regenerate them before you are locked out\./)).toBeInTheDocument();
  });

  it('gives the reason passkeys are unavailable, where the control is', async () => {
    server.use(me());
    stubPasskeys({});
    serve({
      status: {
        ...OFF,
        webauthn: { available: false, reason: 'Set artemis-studio.public-url to use passkeys.' },
      },
    });
    const user = userEvent.setup();
    renderWithProviders(<TwoStepSection />);

    const add = await screen.findByRole('button', { name: 'Add passkey' });
    expect(add).toHaveAccessibleDescription('Not available. Set artemis-studio.public-url to use passkeys.');
    expect(add).toHaveAttribute('aria-disabled', 'true');
    await user.click(add);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('lists trusted devices, marks this one, and revokes one with the outcome announced', async () => {
    const state = {
      status: {
        ...ON,
        trustedDevices: [
          {
            id: 'd1',
            client: FIREFOX,
            address: '203.0.113.7',
            created: ago(600),
            lastUsed: ago(5),
            expires: ahead(12),
            current: true,
          },
          {
            id: 'd2',
            client: 'curl/8.5.0',
            address: null,
            created: ago(900),
            lastUsed: ago(60),
            expires: ahead(20),
            current: false,
          },
        ],
      },
    };
    server.use(
      me(),
      http.delete('*/api/v1/auth/mfa/trusted-devices/d2', () => {
        state.status = { ...state.status, trustedDevices: state.status.trustedDevices.slice(0, 1) };
        return new HttpResponse(null, { status: 204 });
      }),
    );
    serve(state);
    const user = userEvent.setup();
    renderWithProviders(<TwoStepSection />);

    const devices = within(await screen.findByRole('list', { name: 'Trusted devices' }));
    const here = devices.getAllByRole('listitem').find((r) => within(r).queryByText('This device'))!;
    expect(within(here).getByText('Firefox on Linux')).toBeInTheDocument();
    expect(here).toHaveTextContent('203.0.113.7 · trusted');
    expect(within(here).getByText('in 11d')).toBeInTheDocument();
    expect(within(here).getAllByText(/^\(\d{4}-\d{2}-\d{2} /)).toHaveLength(3);

    await user.click(screen.getByRole('button', { name: 'Revoke curl/8.5.0 at an unknown address' }));

    const outcome = await screen.findByText(/Stopped trusting curl\/8\.5\.0 at an unknown address/);
    expect(outcome.closest('[aria-live="polite"]')).not.toBeNull();
    expect(screen.queryByRole('button', { name: /Revoke curl/ })).not.toBeInTheDocument();
  });

  it('revokes every trusted device at once', async () => {
    let revoked = false;
    server.use(
      me(),
      http.delete('*/api/v1/auth/mfa/trusted-devices', () => {
        revoked = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    serve({
      status: {
        ...ON,
        trustedDevices: [
          {
            id: 'd1',
            client: FIREFOX,
            address: '203.0.113.7',
            created: ago(6),
            lastUsed: ago(5),
            expires: ahead(12),
            current: false,
          },
        ],
      },
    });
    const user = userEvent.setup();
    renderWithProviders(<TwoStepSection />);

    await user.click(await screen.findByRole('button', { name: 'Revoke all' }));

    expect(await screen.findByText(/Stopped trusting every device/)).toBeInTheDocument();
    expect(revoked).toBe(true);
  });

  it('regenerates the recovery codes only after saying the old ones stop working, and shows the new ones once', async () => {
    server.use(
      me(),
      http.post('*/api/v1/auth/mfa/recovery-codes', () => HttpResponse.json({ codes: ['ABCDEFGHJK', 'LMNPQRSTUV'] })),
    );
    serve({ status: ON });
    const user = userEvent.setup();
    const { client } = renderWithProviders(<TwoStepSection />);

    await user.click(await screen.findByRole('button', { name: 'Regenerate' }));
    const confirm = await screen.findByRole('dialog', { name: 'Regenerate recovery codes?' });
    expect(within(confirm).getByText(/Your current recovery codes stop working at once/)).toBeInTheDocument();
    await user.click(within(confirm).getByRole('button', { name: 'Regenerate codes' }));

    const codes = await screen.findByRole('dialog', { name: 'Save your recovery codes' });
    expect(within(codes).getByText('ABCDE-FGHJK')).toBeInTheDocument();
    expect(await screen.findByText(/Recovery codes regenerated/)).toBeInTheDocument();
    // Shown once: the cache does not keep the codes behind the dialog while the section stays open.
    await waitFor(() =>
      expect(
        JSON.stringify(
          client
            .getMutationCache()
            .getAll()
            .map((m) => m.state.data),
        ),
      ).not.toContain('ABCDEFGHJK'),
    );
  });

  it('asks for a fresh sign-in inside the confirmation when the server says so, then removes the passkey', async () => {
    let removed = 0;
    server.use(
      me(new Date(Date.now() - 60 * 60_000).toISOString()),
      http.delete('*/api/v1/auth/mfa/webauthn/pk1', () => {
        removed += 1;
        return removed === 1 ? problem('reauthentication-required', 403) : new HttpResponse(null, { status: 204 });
      }),
      http.post('*/api/v1/auth/reauthenticate', () =>
        HttpResponse.json({ status: 'AUTHENTICATED', me: null, trustDeviceDays: 0 }),
      ),
    );
    serve({ status: ON });
    const user = userEvent.setup();
    renderWithProviders(<TwoStepSection />);

    await user.click(await screen.findByRole('button', { name: 'Remove passkey Work laptop' }));
    const dialog = await screen.findByRole('dialog', { name: 'Remove passkey "Work laptop"?' });
    expect(within(dialog).getByText(/Your other methods and your recovery codes keep working/)).toBeInTheDocument();
    await holdButton(within(dialog).getByRole('button', { name: 'Remove passkey' }));

    expect(await within(dialog).findByLabelText('Your password')).toBeInTheDocument();
    expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument();
    expect(removed).toBe(1);
  });

  it('states what removing the only factor costs, and refuses it beforehand when the role requires one', async () => {
    server.use(me());
    serve({ status: { ...OFF, enrolled: true, totpEnrolled: true, recoveryCodesRemaining: 10 } });
    const user = userEvent.setup();
    const { unmount } = renderWithProviders(<TwoStepSection />);

    await user.click(await screen.findByRole('button', { name: 'Remove authenticator app' }));
    const dialog = await screen.findByRole('dialog', { name: 'Remove the authenticator app?' });
    expect(
      within(dialog).getByText(/two-step verification turns off: your recovery codes are deleted/),
    ).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Remove authenticator app' })).toBeEnabled();
    unmount();

    serve({ status: { ...OFF, required: true, enrolled: true, totpEnrolled: true, recoveryCodesRemaining: 10 } });
    renderWithProviders(<TwoStepSection />);
    const refused = await screen.findByRole('button', { name: 'Remove authenticator app' });
    expect(refused).toHaveAttribute('aria-disabled', 'true');
    const reason = screen.getByText(/Your role requires two-step verification\. Add another method first\./);
    expect(refused).toHaveAccessibleDescription(/Add another method first/);
    expect(reason).toBeVisible();
    await user.click(refused);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('surfaces the server message when a removal is refused', async () => {
    server.use(
      me(),
      http.delete('*/api/v1/auth/mfa/totp', () =>
        problem('last-factor-required', 409, 'Add another way to sign in first.'),
      ),
    );
    serve({ status: { ...ON, passkeys: [] } });
    const user = userEvent.setup();
    renderWithProviders(<TwoStepSection />);

    await user.click(await screen.findByRole('button', { name: 'Remove authenticator app' }));
    const dialog = await screen.findByRole('dialog', { name: 'Remove the authenticator app?' });
    await holdButton(within(dialog).getByRole('button', { name: 'Remove authenticator app' }));

    const failure = await screen.findByRole('alert');
    expect(failure).toHaveTextContent('Add another way to sign in first.');
  });

  it('starts the authenticator setup from the account, focus inside the dialog, Escape leaving it', async () => {
    server.use(
      me(),
      http.post('*/api/v1/auth/mfa/totp', () =>
        HttpResponse.json({ secret: 'JBSWY3DPEHPK3PXP', otpauthUri: 'otpauth://totp/x:alice?secret=JBSWY3DPEHPK3PXP' }),
      ),
    );
    serve({ status: OFF });
    const user = userEvent.setup();
    renderWithProviders(<TwoStepSection />);

    const trigger = await screen.findByRole('button', { name: 'Set up authenticator app' });
    await user.click(trigger);

    const dialog = await screen.findByRole('dialog', { name: 'Set up an authenticator app' });
    expect(await within(dialog).findByRole('img', { name: 'QR code for your authenticator app' })).toBeInTheDocument();
    expect(dialog).toContainElement(document.activeElement as HTMLElement);
    // No choice to make here: the account page names the method.
    expect(within(dialog).queryByRole('radio')).not.toBeInTheDocument();

    await user.keyboard('{Escape}');
    await vi.waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(trigger).toHaveFocus();
  });

  it('leaves two-step verification to the identity provider for a single sign-on account', async () => {
    serve({ status: { ...OFF, passwordAccount: false } });
    renderWithProviders(<TwoStepSection />);

    expect(await screen.findByText(/identity provider, which manages two-step verification/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Set up authenticator app' })).not.toBeInTheDocument();
  });

  it('says it could not load, and retries', async () => {
    server.use(
      me(),
      http.get('*/api/v1/auth/mfa', () => problem('boom', 500, 'It broke.')),
    );
    renderWithProviders(<TwoStepSection />);

    expect(await screen.findByRole('alert')).toHaveTextContent('It broke.');
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });
});
