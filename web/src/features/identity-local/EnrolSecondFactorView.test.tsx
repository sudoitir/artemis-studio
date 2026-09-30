import { afterEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { credential, dismissedPrompt, stubPasskeys, unstubPasskeys } from '../../test/passkeys.ts';
import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';

const navigate = vi.fn();

vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useNavigate: () => navigate,
}));

const { EnrolSecondFactorView } = await import('./EnrolSecondFactorView.tsx');

const SECRET = 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP';
const CODES = [
  'ABCDEFGHJK',
  'LMNPQRSTUV',
  'WXYZ234567',
  'ABCDE23456',
  'FGHJK78923',
  'LMNPQ45678',
  'RSTUV34567',
  'WXYZA23456',
  'BCDEF67892',
  'GHJKL34567',
];

const me = (over: object = {}) =>
  http.get('*/api/v1/auth/me', () =>
    HttpResponse.json({
      id: 'u1',
      username: 'alice',
      mustChangePassword: false,
      secondFactorEnrolmentRequired: true,
      grants: [],
      reauthentication: {
        method: 'PASSWORD',
        startPath: null,
        authenticatedAt: new Date().toISOString(),
        windowSeconds: 300,
      },
      ...over,
    }),
  );

const status = (webauthn: { available: boolean; reason?: string | null } = { available: true }) =>
  http.get('*/api/v1/auth/mfa', () =>
    HttpResponse.json({
      required: true,
      enrolled: false,
      totpEnrolled: false,
      recoveryCodesRemaining: 0,
      webauthn,
      passkeys: [],
      trustedDevices: [],
    }),
  );

const totp = http.post('*/api/v1/auth/mfa/totp', () =>
  HttpResponse.json({
    secret: SECRET,
    otpauthUri: `otpauth://totp/Artemis%20Studio:alice?secret=${SECRET}&issuer=Artemis%20Studio`,
  }),
);

afterEach(() => {
  navigate.mockClear();
  unstubPasskeys();
});

describe('EnrolSecondFactorView', () => {
  it('says why, and offers the authenticator app first with its QR code, its key and a code to confirm', async () => {
    server.use(me(), status(), totp);
    renderWithProviders(<EnrolSecondFactorView />);

    expect(screen.getByRole('heading', { name: 'Set up two-step verification' })).toBeInTheDocument();
    expect(screen.getByText('Your role requires a second step when you sign in.')).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Authenticator app' })).toBeChecked();

    const qr = await screen.findByRole('img', { name: 'QR code for your authenticator app' });
    expect(qr.querySelector('path')?.getAttribute('d')).toMatch(/^M\d+ \d+h\d+v1h-\d+z/);
    // Dark on a light field in either colour scheme, so it scans on the dark theme too.
    expect(qr.querySelector('rect')).toHaveAttribute('fill', 'var(--mantine-color-white)');
    expect(qr.querySelector('path')).toHaveAttribute('fill', 'var(--mantine-color-black)');
    expect(screen.getByText('Scan with your authenticator app')).toBeInTheDocument();
    expect(screen.getByText("Can't scan? Enter this key")).toBeInTheDocument();
    expect(screen.getByText('JBSW Y3DP EHPK 3PXP JBSW Y3DP EHPK 3PXP')).toBeInTheDocument();
    expect(screen.getByLabelText('Enter the 6-digit code')).toHaveAttribute('autocomplete', 'one-time-code');
  });

  it('copies the key and announces it', async () => {
    server.use(me(), status(), totp);
    const user = userEvent.setup();
    renderWithProviders(<EnrolSecondFactorView />);

    await user.click(await screen.findByRole('button', { name: 'Copy key' }));

    expect(await screen.findByText('Key copied to the clipboard.')).toBeInTheDocument();
    expect(await navigator.clipboard.readText()).toBe(SECRET);
  });

  it('checks the code, shows the recovery codes once, and opens the console only after they were saved', async () => {
    let confirmed: unknown;
    server.use(
      me(),
      status(),
      totp,
      http.post('*/api/v1/auth/mfa/totp/confirm', async ({ request }) => {
        confirmed = await request.json();
        return HttpResponse.json({ recoveryCodes: CODES });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<EnrolSecondFactorView />);

    await screen.findByRole('img', { name: 'QR code for your authenticator app' });
    await user.type(screen.getByLabelText('Enter the 6-digit code'), '12{Enter}');
    expect(await screen.findByText('Enter the 6 digits your app shows.')).toBeInTheDocument();
    expect(confirmed).toBeUndefined();

    await user.clear(screen.getByLabelText('Enter the 6-digit code'));
    await user.type(screen.getByLabelText('Enter the 6-digit code'), '123456{Enter}');

    const dialog = await screen.findByRole('dialog', { name: 'Save your recovery codes' });
    expect(confirmed).toEqual({ code: '123456' });
    expect(within(dialog).getAllByRole('listitem')).toHaveLength(10);
    expect(within(dialog).getByText('ABCDE-FGHJK')).toBeInTheDocument();

    // Not silently dismissed: Escape does nothing, and Continue names what is missing.
    await user.keyboard('{Escape}');
    expect(screen.getByRole('dialog', { name: 'Save your recovery codes' })).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Continue' }));
    expect(await within(dialog).findByText('Confirm you saved the codes before continuing.')).toBeInTheDocument();
    expect(navigate).not.toHaveBeenCalled();

    await user.click(within(dialog).getByRole('checkbox', { name: 'I saved these codes somewhere safe' }));
    await user.click(within(dialog).getByRole('button', { name: 'Continue' }));

    expect(navigate).toHaveBeenCalledWith({ to: '/' });
  });

  it('downloads the codes as a text file named for the product, and copies them all', async () => {
    const created = vi.fn(() => 'blob:codes');
    const revoked = vi.fn();
    Object.assign(URL, { createObjectURL: created, revokeObjectURL: revoked });
    let filename = '';
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      filename = this.download;
    });
    server.use(
      me(),
      status(),
      totp,
      http.post('*/api/v1/auth/mfa/totp/confirm', () => HttpResponse.json({ recoveryCodes: CODES })),
    );
    const user = userEvent.setup();
    renderWithProviders(<EnrolSecondFactorView />);
    await screen.findByRole('img', { name: 'QR code for your authenticator app' });
    await user.type(screen.getByLabelText('Enter the 6-digit code'), '123456{Enter}');
    const dialog = await screen.findByRole('dialog', { name: 'Save your recovery codes' });

    await user.click(within(dialog).getByRole('button', { name: 'Download' }));
    await user.click(within(dialog).getByRole('button', { name: 'Copy all' }));

    expect(filename).toBe('artemis-studio-recovery-codes.txt');
    expect(await within(dialog).findByText('Recovery codes copied to the clipboard.')).toBeInTheDocument();
    expect((await navigator.clipboard.readText()).split('\n')).toHaveLength(10);
    click.mockRestore();
  });

  it('says a wrong code is wrong, beside the field', async () => {
    server.use(
      me(),
      status(),
      totp,
      http.post('*/api/v1/auth/mfa/totp/confirm', () =>
        HttpResponse.json(
          { type: 'https://artemis-studio.dev/problems/invalid-value', title: 'Invalid' },
          { status: 400 },
        ),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<EnrolSecondFactorView />);
    await screen.findByRole('img', { name: 'QR code for your authenticator app' });

    await user.type(screen.getByLabelText('Enter the 6-digit code'), '123456{Enter}');

    expect(await screen.findByText(/That code is not right/)).toBeInTheDocument();
    expect(screen.getByLabelText('Enter the 6-digit code')).toHaveFocus();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('keeps the passkey choice reachable, and says in words why it is unavailable', async () => {
    stubPasskeys({});
    server.use(
      me(),
      status({
        available: false,
        reason: "Studio's public address is not set. Set artemis-studio.public-url to use passkeys.",
      }),
      totp,
    );
    const user = userEvent.setup();
    renderWithProviders(<EnrolSecondFactorView />);

    const passkey = await screen.findByRole('radio', { name: 'Passkey' });
    await waitFor(() =>
      expect(passkey).toHaveAccessibleDescription(/Not available\. Studio's public address is not set/),
    );
    expect(passkey).toHaveAttribute('aria-disabled', 'true');
    await user.click(passkey);
    // The choice does not move.
    expect(screen.getByRole('radio', { name: 'Authenticator app' })).toBeChecked();

    // Keyboard: tab to the group, arrow to the passkey; the reason is read and nothing changes.
    screen.getByRole('radio', { name: 'Authenticator app' }).focus();
    await user.keyboard('{ArrowDown}');
    expect(screen.getByRole('radio', { name: 'Authenticator app' })).toBeChecked();
  });

  it('creates a passkey with a name, and shows the recovery codes', async () => {
    let registered: unknown;
    stubPasskeys({ create: async () => credential({ id: 'cred-1', type: 'public-key' }) });
    server.use(
      me(),
      status(),
      totp,
      http.post('*/api/v1/auth/mfa/webauthn/options', () =>
        HttpResponse.json({ challenge: 'abc', rp: { id: 'localhost' } }),
      ),
      http.post('*/api/v1/auth/mfa/webauthn', async ({ request }) => {
        registered = await request.json();
        return HttpResponse.json({
          passkey: {
            id: 'cred-1',
            label: 'Laptop',
            created: new Date().toISOString(),
            lastUsed: new Date().toISOString(),
          },
          recoveryCodes: CODES,
        });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<EnrolSecondFactorView />);

    await user.click(await screen.findByRole('radio', { name: 'Passkey' }));
    const label = screen.getByLabelText('Passkey name');
    await user.clear(label);
    await user.type(label, 'Laptop');
    await user.click(screen.getByRole('button', { name: 'Create passkey' }));

    expect(await screen.findByRole('dialog', { name: 'Save your recovery codes' })).toBeInTheDocument();
    expect(registered).toEqual({ label: 'Laptop', credential: { id: 'cred-1', type: 'public-key' } });
    expect(PublicKeyCredential.parseCreationOptionsFromJSON).toHaveBeenCalled();
  });

  it('names the passkey after the browser by default, and takes a dismissed prompt calmly', async () => {
    stubPasskeys({
      create: async () => {
        throw dismissedPrompt();
      },
    });
    server.use(
      me(),
      status(),
      totp,
      http.post('*/api/v1/auth/mfa/webauthn/options', () => HttpResponse.json({ challenge: 'abc' })),
    );
    const user = userEvent.setup();
    renderWithProviders(<EnrolSecondFactorView />);

    await user.click(await screen.findByRole('radio', { name: 'Passkey' }));
    expect(screen.getByLabelText('Passkey name')).toHaveValue('This device');
    await user.click(screen.getByRole('button', { name: 'Create passkey' }));

    expect(await screen.findByText('Passkey prompt was dismissed. Try again when you are ready.')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('goes to the account page when nothing requires enrolment, and to the password change first when due', async () => {
    server.use(me({ secondFactorEnrolmentRequired: false }), status(), totp);
    const { unmount } = renderWithProviders(<EnrolSecondFactorView />);
    await waitFor(() => expect(navigate).toHaveBeenCalledWith({ to: '/account' }));
    unmount();
    navigate.mockClear();

    server.use(me({ mustChangePassword: true }));
    renderWithProviders(<EnrolSecondFactorView />);
    await waitFor(() => expect(navigate).toHaveBeenCalledWith({ to: '/change-password' }));
  });
});
