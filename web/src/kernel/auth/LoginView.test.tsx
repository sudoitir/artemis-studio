import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';

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
    server.use(http.get('*/api/v1/auth/providers', () => HttpResponse.json([LOCAL])));
    setBootState(BOOTED);
  });
  afterEach(() => {
    navigate.mockClear();
    setBootState({ plugins: [], failures: new Map() });
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

  it('does not sign in when the password alone is not enough', async () => {
    server.use(
      http.post('*/api/v1/auth/login', () =>
        HttpResponse.json({ status: 'SECOND_FACTOR_REQUIRED', me: null, methods: ['TOTP', 'RECOVERY_CODE'] }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<LoginView />);

    await user.type(screen.getByLabelText(/Username/), 'alice');
    await user.type(screen.getByLabelText(/Password/), 'secret123');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    await vi.waitFor(() => expect(screen.getByRole('button', { name: 'Sign in' })).toBeEnabled());
    expect(navigate).not.toHaveBeenCalled();
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

    expect(await screen.findByText('Invalid username or password.')).toBeInTheDocument();
    expect(navigate).not.toHaveBeenCalled();
  });

  it('shows a sign-in action per redirect provider beside the form', async () => {
    server.use(
      http.get('*/api/v1/auth/providers', () =>
        HttpResponse.json([
          LOCAL,
          { id: 'okta', kind: 'REDIRECT', label: 'Okta', startPath: '/oauth2/authorization/okta' },
        ]),
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
        HttpResponse.json([LOCAL, { id: 'directory', kind: 'CREDENTIAL', label: 'Directory', startPath: null }]),
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
