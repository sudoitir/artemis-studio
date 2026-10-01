import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';

function mockChange(status: number, body: Record<string, unknown>) {
  server.use(
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({ id: 'u1', username: 'operator', mustChangePassword: false, grants: [] }),
    ),
    http.post('*/api/v1/auth/password', () =>
      HttpResponse.json(body, { status, headers: { 'Content-Type': 'application/problem+json' } }),
    ),
  );
}

async function submit() {
  renderAppAt('/change-password');
  await userEvent.type(await screen.findByLabelText(/Current password/), 'current-password');
  await userEvent.type(screen.getByLabelText(/^New password/), 'short');
  await userEvent.type(screen.getByLabelText(/Confirm new password/), 'short');
  await userEvent.click(screen.getByRole('button', { name: 'Change password' }));
}

describe('ChangePasswordView', () => {
  it("shows the password policy's reason beside the new password", async () => {
    mockChange(400, {
      type: 'https://artemis-studio.dev/problems/password-policy',
      title: 'Password not accepted',
      detail: 'Use at least 12 characters.',
    });

    await submit();

    expect(await screen.findByText('Use at least 12 characters.')).toBeInTheDocument();
    expect(screen.queryByText(/Current password is incorrect/)).not.toBeInTheDocument();
  });

  it('says the current password is wrong on a 401', async () => {
    mockChange(401, {
      type: 'https://artemis-studio.dev/problems/invalid-credentials',
      title: 'Authentication failed',
      detail: 'Invalid username or password.',
    });

    await submit();

    expect(await screen.findByText('Current password is incorrect. Re-enter it and try again.')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByLabelText(/Current password/)).toHaveFocus());
  });
});

describe('ChangePasswordView form', () => {
  it('is one page with one top-level heading, and keeps the button live while naming what is missing', async () => {
    let sent = 0;
    server.use(
      http.get('*/api/v1/auth/me', () =>
        HttpResponse.json({ id: 'u1', username: 'operator', mustChangePassword: false, grants: [] }),
      ),
      http.post('*/api/v1/auth/password', () => {
        sent += 1;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderAppAt('/change-password');

    const button = await screen.findByRole('button', { name: 'Change password' });
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.getByRole('heading', { name: 'Change your password', level: 1 })).toBeInTheDocument();
    expect(button).toBeEnabled();
    await userEvent.click(button);

    expect(await screen.findByText('Enter your current password.')).toBeInTheDocument();
    expect(screen.getByText('Enter a new password.')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByLabelText(/Current password/)).toHaveFocus());
    expect(sent).toBe(0);
  });

  it('names a mismatch beside the confirmation and sends nothing', async () => {
    let sent = 0;
    server.use(
      http.get('*/api/v1/auth/me', () =>
        HttpResponse.json({ id: 'u1', username: 'operator', mustChangePassword: false, grants: [] }),
      ),
      http.post('*/api/v1/auth/password', () => {
        sent += 1;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderAppAt('/change-password');

    await userEvent.type(await screen.findByLabelText(/Current password/), 'current-password');
    await userEvent.type(screen.getByLabelText(/^New password/), 'a-long-new-password');
    await userEvent.type(screen.getByLabelText(/Confirm new password/), 'a-different-one');
    await userEvent.click(screen.getByRole('button', { name: 'Change password' }));

    expect(await screen.findByText('Passwords do not match.')).toBeInTheDocument();
    expect(sent).toBe(0);
  });
});
