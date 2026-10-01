import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { credential, dismissedPrompt, stubPasskeys, unstubPasskeys } from '../../test/passkeys.ts';
import { server } from '../../test/setup.ts';
import { paged } from '../api/paging.ts';

const navigate = vi.fn();

vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useNavigate: () => navigate,
}));

const { LoginView } = await import('./LoginView.tsx');
const { setBootState } = await import('../plugins/boot.ts');
const BOOTED = { manifest: { version: '1' } as never, plugins: [], failures: new Map<string, string>() };

const LOCAL = { id: 'local', kind: 'CREDENTIAL', label: 'Password', startPath: null };

/** What `POST /auth/login` answers once the sign-in is complete. */
function signedIn(username: string, mustChangePassword: boolean) {
  return {
    status: 'AUTHENTICATED',
    me: { id: 'u1', username, mustChangePassword, secondFactorEnrolmentRequired: false, grants: [] },
  };
}

describe('LoginView', () => {
  beforeEach(() => {
    server.use(http.get('*/api/v1/auth/providers', () => HttpResponse.json(paged([LOCAL]))));
    setBootState(BOOTED);
  });
  afterEach(() => {
    navigate.mockClear();
    setBootState({ plugins: [], failures: new Map() });
  });

  it('is one page with one top-level heading that names the view, the product beside it', async () => {
    renderWithProviders(<LoginView />);

    const headings = await screen.findAllByRole('heading', { level: 1 });
    expect(headings).toHaveLength(1);
    expect(headings[0]).toHaveTextContent('Sign in');
    expect(screen.getByRole('main')).toHaveTextContent('Artemis Studio');
  });

  it('keeps Sign in live, names what is missing beside each field, focuses the first and sends nothing', async () => {
    let sent = 0;
    server.use(
      http.post('*/api/v1/auth/login', () => {
        sent += 1;
        return HttpResponse.json(signedIn('alice', false));
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<LoginView />);

    const button = await screen.findByRole('button', { name: 'Sign in' });
    expect(button).toBeEnabled();
    await user.click(button);

    expect(screen.getByText('Enter your username.')).toBeInTheDocument();
    expect(screen.getByText('Enter your password.')).toBeInTheDocument();
    expect(screen.getByLabelText(/Username/)).toHaveFocus();
    expect(sent).toBe(0);
  });

  it('names a missing password when the field is left, before anything is pressed', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LoginView />);

    await user.type(screen.getByLabelText(/Username/), 'alice');
    await user.click(screen.getByLabelText(/Password/));
    await user.tab();

    expect(await screen.findByText('Enter your password.')).toBeInTheDocument();
  });

  it('says to wait when a sign-in is rate limited, and what to do next', async () => {
    server.use(
      http.post('*/api/v1/auth/login', () => HttpResponse.json({ title: 'Too many requests' }, { status: 429 })),
    );
    const user = userEvent.setup();
    renderWithProviders(<LoginView />);

    await user.type(screen.getByLabelText(/Username/), 'alice');
    await user.type(screen.getByLabelText(/Password/), 'secret123');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Too many attempts. Wait a moment, then try again.');
  });

  it('starts the page again after sign-in when it started signed out, so plugins load', async () => {
    setBootState({ plugins: [], failures: new Map() });
    const replace = vi.fn();
    vi.stubGlobal('location', { ...window.location, replace, pathname: '/login' });
    server.use(http.post('*/api/v1/auth/login', () => HttpResponse.json(signedIn('alice', false))));
    const user = userEvent.setup();
    renderWithProviders(<LoginView />);

    await user.type(screen.getByLabelText(/Username/), 'alice');
    await user.type(screen.getByLabelText(/Password/), 'secret123');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    await vi.waitFor(() => expect(replace).toHaveBeenCalledWith('/'));
    expect(navigate).not.toHaveBeenCalled();
    vi.unstubAllGlobals();
  });

  it('submits credentials and navigates home on success', async () => {
    server.use(http.post('*/api/v1/auth/login', () => HttpResponse.json(signedIn('alice', false))));
    const user = userEvent.setup();
    renderWithProviders(<LoginView />);

    await user.type(screen.getByLabelText(/Username/), 'alice');
    await user.type(screen.getByLabelText(/Password/), 'secret123');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    await vi.waitFor(() => expect(navigate).toHaveBeenCalledWith({ to: '/' }));
  });

  it('navigates to change-password when the account must change it', async () => {
    server.use(http.post('*/api/v1/auth/login', () => HttpResponse.json(signedIn('admin', true))));
    const user = userEvent.setup();
    renderWithProviders(<LoginView />);

    await user.type(screen.getByLabelText(/Username/), 'admin');
    await user.type(screen.getByLabelText(/Password/), 'generated');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    await vi.waitFor(() => expect(navigate).toHaveBeenCalledWith({ to: '/change-password' }));
  });

  it('shows an error message on invalid credentials', async () => {
    server.use(
      http.post('*/api/v1/auth/login', () =>
        HttpResponse.json(
          { type: 'https://artemis-studio.dev/problems/invalid-credentials', title: 'Authentication failed' },
          { status: 401 },
        ),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<LoginView />);

    await user.type(screen.getByLabelText(/Username/), 'alice');
    await user.type(screen.getByLabelText(/Password/), 'wrong');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Invalid username or password. Check both and try again.',
    );
    expect(navigate).not.toHaveBeenCalled();
  });

  it('shows a sign-in action per redirect provider beside the form', async () => {
    server.use(
      http.get('*/api/v1/auth/providers', () =>
        HttpResponse.json(
          paged([LOCAL, { id: 'okta', kind: 'REDIRECT', label: 'Okta', startPath: '/oauth2/authorization/okta' }]),
        ),
      ),
    );
    renderWithProviders(<LoginView />);

    const link = await screen.findByRole('link', { name: 'Sign in with Okta' });
    expect(link).toHaveAttribute('href', '/oauth2/authorization/okta');
    expect(screen.getByLabelText(/Username/)).toBeInTheDocument();
  });

  it('offers a choice between credential providers and sends the chosen one', async () => {
    let body: unknown;
    server.use(
      http.get('*/api/v1/auth/providers', () =>
        HttpResponse.json(paged([LOCAL, { id: 'directory', kind: 'CREDENTIAL', label: 'Directory', startPath: null }])),
      ),
      http.post('*/api/v1/auth/login', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(signedIn('alice', false));
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<LoginView />);

    // Mantine labels both the input and its option list; the choice is the input.
    const choice = (await screen.findAllByLabelText('Sign in with')).find((el) => el.tagName === 'INPUT');
    expect(choice).toBeInTheDocument();
    await user.type(screen.getByLabelText(/Username/), 'alice');
    await user.type(screen.getByLabelText(/Password/), 'secret123');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    await vi.waitFor(() => expect(body).toEqual({ provider: 'local', username: 'alice', password: 'secret123' }));
  });

  it('says when the sign-in methods could not be loaded, keeps the password form and retries', async () => {
    let healthy = false;
    server.use(
      http.get('*/api/v1/auth/providers', () =>
        healthy
          ? HttpResponse.json(
              paged([LOCAL, { id: 'acme:corp', kind: 'CREDENTIAL', label: 'Directory', startPath: null }]),
            )
          : HttpResponse.json({ type: 'about:blank', title: 'boom', status: 500 }, { status: 500 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<LoginView />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Studio failed to complete the request');
    expect(screen.getByText(/Password sign-in is still offered/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Username/)).toBeInTheDocument();

    healthy = true;
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));

    expect((await screen.findAllByLabelText('Sign in with')).length).toBeGreaterThan(0);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('says the session ended when sent back from a signed-in page, and says nothing on a plain visit', async () => {
    vi.stubGlobal('location', { ...window.location, search: '?reason=ended', pathname: '/login' });
    const { unmount } = renderWithProviders(<LoginView />);

    expect(await screen.findByRole('status')).toHaveTextContent(/You were signed out/);
    expect(screen.getByRole('status')).toHaveTextContent(/inactivity/);
    unmount();

    vi.stubGlobal('location', { ...window.location, search: '', pathname: '/login' });
    renderWithProviders(<LoginView />);

    await screen.findByLabelText(/Username/);
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    vi.unstubAllGlobals();
  });
});

const problem = (slug: string, status: number, detail?: string) =>
  HttpResponse.json({ type: `https://artemis-studio.dev/problems/${slug}`, title: slug, detail }, { status });

/** A sign-in whose password is right and whose account has a second factor. */
function secondStep(methods: string[], trustDeviceDays = 0) {
  return http.post('*/api/v1/auth/login', () =>
    HttpResponse.json({ status: 'SECOND_FACTOR_REQUIRED', me: null, methods, trustDeviceDays }),
  );
}

async function passwordStep(user: ReturnType<typeof userEvent.setup>) {
  await user.type(await screen.findByLabelText(/Username/), 'alice');
  await user.type(screen.getByLabelText(/Password/), 'secret123');
  await user.click(screen.getByRole('button', { name: 'Sign in' }));
}

describe('LoginView second step', () => {
  beforeEach(() => {
    server.use(http.get('*/api/v1/auth/providers', () => HttpResponse.json(paged([LOCAL]))));
    setBootState(BOOTED);
  });
  afterEach(() => {
    navigate.mockClear();
    unstubPasskeys();
    setBootState({ plugins: [], failures: new Map() });
  });

  it('asks for the code after the password, signs in only with it, and sends it by keyboard', async () => {
    let body: unknown;
    server.use(
      secondStep(['TOTP', 'RECOVERY_CODE']),
      http.post('*/api/v1/auth/second-factor', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(signedIn('alice', false));
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<LoginView />);

    await passwordStep(user);

    const code = await screen.findByLabelText('Code from your authenticator app');
    expect(code).toHaveAttribute('autocomplete', 'one-time-code');
    expect(code).toHaveAttribute('inputmode', 'numeric');
    expect(code).toHaveFocus();
    expect(screen.queryByRole('button', { name: 'Sign in' })).not.toBeInTheDocument();
    expect(navigate).not.toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: 'Use a passkey' })).not.toBeInTheDocument();
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();

    await user.type(code, '123456{Enter}');

    await vi.waitFor(() => expect(navigate).toHaveBeenCalledWith({ to: '/' }));
    expect(body).toEqual({ totpCode: '123456' });
  });

  it('checks the code before sending it, and puts the person back on the field', async () => {
    server.use(secondStep(['TOTP', 'RECOVERY_CODE']));
    const user = userEvent.setup();
    renderWithProviders(<LoginView />);
    await passwordStep(user);

    await user.type(await screen.findByLabelText('Code from your authenticator app'), '12{Enter}');

    expect(await screen.findByText('Enter the 6 digits from your authenticator app.')).toBeInTheDocument();
    expect(screen.getByLabelText('Code from your authenticator app')).toHaveFocus();
  });

  it('says a wrong code was not accepted, beside the field, and stays on the step', async () => {
    server.use(
      secondStep(['TOTP', 'RECOVERY_CODE']),
      http.post('*/api/v1/auth/second-factor', () => problem('second-factor-invalid', 401)),
    );
    const user = userEvent.setup();
    renderWithProviders(<LoginView />);
    await passwordStep(user);

    await user.type(await screen.findByLabelText('Code from your authenticator app'), '654321{Enter}');

    expect(await screen.findByText(/That code was not accepted\. Enter the current code/)).toBeInTheDocument();
    expect(screen.getByLabelText('Code from your authenticator app')).toBeInTheDocument();
    expect(navigate).not.toHaveBeenCalled();
  });

  it('switches to a recovery code and back', async () => {
    let body: unknown;
    server.use(
      secondStep(['TOTP', 'RECOVERY_CODE']),
      http.post('*/api/v1/auth/second-factor', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(signedIn('alice', false));
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<LoginView />);
    await passwordStep(user);

    await user.click(await screen.findByRole('button', { name: 'Use a recovery code instead' }));
    const recovery = screen.getByLabelText('Recovery code');
    expect(recovery).toHaveFocus();
    expect(screen.getByText(/XXXXX-XXXXX/)).toBeInTheDocument();
    expect(screen.queryByLabelText('Code from your authenticator app')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Use an authenticator code instead' }));
    expect(screen.getByLabelText('Code from your authenticator app')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Use a recovery code instead' }));
    await user.type(screen.getByLabelText('Recovery code'), 'ABCDE-FGHJK{Enter}');

    await vi.waitFor(() => expect(navigate).toHaveBeenCalledWith({ to: '/' }));
    expect(body).toEqual({ recoveryCode: 'ABCDE-FGHJK' });
  });

  it('signs in with a passkey: options from the server, the browser prompt, the credential back', async () => {
    let body: unknown;
    stubPasskeys({ get: async () => credential({ id: 'cred-1', type: 'public-key' }) });
    server.use(
      secondStep(['WEBAUTHN', 'RECOVERY_CODE']),
      http.post('*/api/v1/auth/second-factor/options', () =>
        HttpResponse.json({ challenge: 'abc', rpId: 'localhost' }),
      ),
      http.post('*/api/v1/auth/second-factor', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(signedIn('alice', false));
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<LoginView />);
    await passwordStep(user);

    // The passkey comes first: it holds the focus, and the code is the alternative below it.
    const passkey = await screen.findByRole('button', { name: 'Use a passkey' });
    expect(passkey).toHaveFocus();
    await user.click(passkey);

    await vi.waitFor(() => expect(navigate).toHaveBeenCalledWith({ to: '/' }));
    expect(body).toEqual({ webauthn: { id: 'cred-1', type: 'public-key' } });
    expect(PublicKeyCredential.parseRequestOptionsFromJSON).toHaveBeenCalledWith({
      challenge: 'abc',
      rpId: 'localhost',
    });
  });

  it('takes a dismissed passkey prompt calmly and leaves the code to try', async () => {
    stubPasskeys({
      get: async () => {
        throw dismissedPrompt();
      },
    });
    server.use(
      secondStep(['WEBAUTHN', 'TOTP', 'RECOVERY_CODE']),
      http.post('*/api/v1/auth/second-factor/options', () => HttpResponse.json({ challenge: 'abc' })),
    );
    const user = userEvent.setup();
    renderWithProviders(<LoginView />);
    await passwordStep(user);

    await user.click(await screen.findByRole('button', { name: 'Use a passkey' }));

    const notice = await screen.findByText('Passkey prompt was dismissed. Try again, or use a code.');
    expect(notice.closest('[aria-live="polite"]')).not.toBeNull();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Use a passkey' })).toBeEnabled();
    expect(screen.getByLabelText('Code from your authenticator app')).toBeInTheDocument();
  });

  it('says so when this browser cannot do passkeys, and offers the code', async () => {
    server.use(secondStep(['WEBAUTHN', 'TOTP', 'RECOVERY_CODE']));
    const user = userEvent.setup();
    renderWithProviders(<LoginView />);
    await passwordStep(user);

    expect(await screen.findByRole('button', { name: 'Use a passkey' })).toBeDisabled();
    expect(screen.getByText(/This browser cannot use passkeys/)).toBeInTheDocument();
    expect(screen.getByLabelText('Code from your authenticator app')).toHaveFocus();
  });

  it('offers to trust the device only when the administrator allows it, and sends the choice', async () => {
    let body: unknown;
    server.use(
      secondStep(['TOTP', 'RECOVERY_CODE'], 30),
      http.post('*/api/v1/auth/second-factor', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(signedIn('alice', false));
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<LoginView />);
    await passwordStep(user);

    const trust = await screen.findByRole('checkbox', { name: 'Trust this device for 30 days' });
    expect(screen.getByText(/Confirming a sensitive action still asks for a code/)).toBeInTheDocument();
    await user.click(trust);
    await user.type(screen.getByLabelText('Code from your authenticator app'), '123456{Enter}');

    await vi.waitFor(() => expect(body).toEqual({ totpCode: '123456', trustDevice: true }));
  });

  it('returns to the password when the sign-in timed out, and says so', async () => {
    server.use(
      secondStep(['TOTP', 'RECOVERY_CODE']),
      http.post('*/api/v1/auth/second-factor', () => problem('sign-in-expired', 401)),
    );
    const user = userEvent.setup();
    renderWithProviders(<LoginView />);
    await passwordStep(user);

    await user.type(await screen.findByLabelText('Code from your authenticator app'), '123456{Enter}');

    expect(await screen.findByText('Your sign-in timed out. Enter your password again.')).toBeInTheDocument();
    expect(screen.getByLabelText(/Password/)).toHaveValue('');
    expect(screen.getByLabelText(/Password/)).toHaveFocus();
    expect(screen.getByLabelText(/Username/)).toHaveValue('alice');
  });

  it('gives a locked account the words of a wrong password, back on the password step', async () => {
    server.use(
      secondStep(['TOTP', 'RECOVERY_CODE']),
      http.post('*/api/v1/auth/second-factor', () => problem('invalid-credentials', 401)),
    );
    const user = userEvent.setup();
    renderWithProviders(<LoginView />);
    await passwordStep(user);

    await user.type(await screen.findByLabelText('Code from your authenticator app'), '123456{Enter}');

    expect(await screen.findByText('Invalid username or password.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument();
  });

  it('says to wait when too many attempts were made', async () => {
    server.use(
      secondStep(['TOTP', 'RECOVERY_CODE']),
      http.post('*/api/v1/auth/second-factor', () => problem('login-throttled', 429)),
    );
    const user = userEvent.setup();
    renderWithProviders(<LoginView />);
    await passwordStep(user);

    await user.type(await screen.findByLabelText('Code from your authenticator app'), '123456{Enter}');

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Too many attempts. Wait a few minutes, then try again.',
    );
  });

  it('goes back to the password with Back', async () => {
    server.use(secondStep(['TOTP', 'RECOVERY_CODE']));
    const user = userEvent.setup();
    renderWithProviders(<LoginView />);
    await passwordStep(user);

    await user.click(await screen.findByRole('button', { name: 'Back' }));

    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.getByLabelText(/Password/)).toHaveFocus();
  });

  it('sends an account that must enrol a second factor to the enrolment screen', async () => {
    server.use(
      http.post('*/api/v1/auth/login', () =>
        HttpResponse.json({
          status: 'AUTHENTICATED',
          me: {
            id: 'u1',
            username: 'alice',
            mustChangePassword: false,
            secondFactorEnrolmentRequired: true,
            grants: [],
          },
        }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<LoginView />);

    await passwordStep(user);

    await vi.waitFor(() => expect(navigate).toHaveBeenCalledWith({ to: '/enrol-second-factor' }));
  });
});
