import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { ApiError } from '../api/request.ts';
import { credential, stubPasskeys, unstubPasskeys } from '../../test/passkeys.ts';
import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { StepUp, StepUpPrompt } from './StepUp.tsx';

const AN_HOUR_AGO = new Date(Date.now() - 60 * 60_000).toISOString();

const me = (over: object = {}) =>
  http.get('*/api/v1/auth/me', () =>
    HttpResponse.json({
      id: 'u1',
      username: 'alice',
      mustChangePassword: false,
      secondFactorEnrolmentRequired: false,
      grants: [],
      reauthentication: {
        method: 'PASSWORD',
        startPath: null,
        authenticatedAt: AN_HOUR_AGO,
        windowSeconds: 300,
        ...over,
      },
    }),
  );

const problem = (slug: string, status: number) =>
  HttpResponse.json({ type: `https://artemis-studio.dev/problems/${slug}`, title: slug }, { status });

const needsFactor = (methods: string[]) =>
  http.post('*/api/v1/auth/reauthenticate', () =>
    HttpResponse.json({ status: 'SECOND_FACTOR_REQUIRED', me: null, methods, trustDeviceDays: 0 }),
  );

describe('StepUp', () => {
  const originalLocation = window.location;
  beforeEach(() => {
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: { ...originalLocation, pathname: '/account', search: '', assign: vi.fn() },
    });
  });
  afterEach(() => {
    unstubPasskeys();
    Object.defineProperty(window, 'location', { configurable: true, value: originalLocation });
  });

  it('asks an account with a second factor for it after the password, and finishes the step-up', async () => {
    let body: unknown;
    server.use(
      me(),
      needsFactor(['TOTP', 'RECOVERY_CODE']),
      http.post('*/api/v1/auth/second-factor', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ status: 'AUTHENTICATED', me: null, trustDeviceDays: 0 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<StepUp returnTo="/account" />);

    await user.type(await screen.findByLabelText('Your password'), 'secret{Enter}');

    const code = await screen.findByLabelText('Code from your authenticator app');
    // A step-up never offers to trust the device.
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
    await user.type(code, '123456{Enter}');

    await vi.waitFor(() => expect(body).toEqual({ totpCode: '123456' }));
  });

  it('keeps the person here when the code is wrong, instead of sending them to sign in again', async () => {
    server.use(
      me(),
      needsFactor(['TOTP', 'RECOVERY_CODE']),
      http.post('*/api/v1/auth/second-factor', () => problem('second-factor-invalid', 401)),
    );
    const user = userEvent.setup();
    renderWithProviders(<StepUp returnTo="/account" />);
    await user.type(await screen.findByLabelText('Your password'), 'secret{Enter}');

    await user.type(await screen.findByLabelText('Code from your authenticator app'), '000000{Enter}');

    expect(await screen.findByText(/That code was not accepted/)).toBeInTheDocument();
    expect(window.location.assign).not.toHaveBeenCalled();
  });

  it('uses a passkey for the step-up', async () => {
    let body: unknown;
    stubPasskeys({ get: async () => credential({ id: 'cred-1' }) });
    server.use(
      me(),
      needsFactor(['WEBAUTHN', 'RECOVERY_CODE']),
      http.post('*/api/v1/auth/second-factor/options', () => HttpResponse.json({ challenge: 'abc' })),
      http.post('*/api/v1/auth/second-factor', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ status: 'AUTHENTICATED', me: null, trustDeviceDays: 0 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<StepUp returnTo="/account" />);
    await user.type(await screen.findByLabelText('Your password'), 'secret{Enter}');

    await user.click(await screen.findByRole('button', { name: 'Use a passkey' }));

    await vi.waitFor(() => expect(body).toEqual({ webauthn: { id: 'cred-1' } }));
  });

  it('goes back to the password when Back is chosen', async () => {
    server.use(me(), needsFactor(['TOTP', 'RECOVERY_CODE']));
    const user = userEvent.setup();
    renderWithProviders(<StepUp returnTo="/account" />);
    await user.type(await screen.findByLabelText('Your password'), 'secret{Enter}');

    await user.click(await screen.findByRole('button', { name: 'Back' }));

    expect(screen.getByLabelText('Your password')).toBeInTheDocument();
  });

  it('shows nothing once the session is fresh, and offers the identity provider to an SSO session', async () => {
    server.use(me({ authenticatedAt: new Date().toISOString() }));
    const { unmount } = renderWithProviders(<StepUp returnTo="/account" />);
    // Give `/auth/me` time to arrive: a fresh session shows no prompt of either kind.
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(screen.queryByLabelText('Your password')).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Sign in again' })).not.toBeInTheDocument();
    unmount();

    server.use(me({ method: 'REDIRECT', startPath: '/oauth2/authorization/okta?x=1' }));
    renderWithProviders(<StepUp returnTo="/account?tab=x" />);
    expect(await screen.findByRole('link', { name: 'Sign in again' })).toHaveAttribute(
      'href',
      '/oauth2/authorization/okta?x=1&returnTo=%2Faccount%3Ftab%3Dx',
    );
  });
});

describe('StepUpPrompt', () => {
  const refusal = new ApiError(403, {
    type: 'https://artemis-studio.dev/problems/reauthentication-required',
    title: 'Confirm it is you',
  });

  it('shows the step-up for the error that asks for a fresh sign-in', async () => {
    server.use(me());
    renderWithProviders(<StepUpPrompt error={refusal} returnTo="/" />);

    expect(await screen.findByLabelText('Your password')).toBeInTheDocument();
  });

  it('says to try again once the session is fresh', async () => {
    server.use(me({ authenticatedAt: new Date().toISOString() }));
    renderWithProviders(<StepUpPrompt error={refusal} returnTo="/" />);

    expect(await screen.findByText('Confirmed. Try again.')).toBeInTheDocument();
    expect(screen.queryByLabelText('Your password')).not.toBeInTheDocument();
  });

  it('shows nothing for any other error', () => {
    server.use(me());
    renderWithProviders(
      <StepUpPrompt error={new ApiError(409, { type: 'x/last-factor-required', title: 'x' })} returnTo="/" />,
    );

    expect(screen.queryByLabelText('Your password')).not.toBeInTheDocument();
    expect(screen.queryByText('Confirmed. Try again.')).not.toBeInTheDocument();
  });
});
