import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { paged } from '../../kernel/api/paging.ts';

// The link needs a router to resolve; its target is all this test cares about.
vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  Link: ({ to, children }: { to: string; children: React.ReactNode }) => <a href={to}>{children}</a>,
}));

const { PasswordSection } = await import('./sections.tsx');

const serve = (providerId: string) =>
  server.use(
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        id: 'u1',
        username: 'alice',
        mustChangePassword: false,
        providerId,
        secondFactorEnrolmentRequired: false,
        grants: [],
        reauthentication: { method: 'PASSWORD', startPath: null, authenticatedAt: null, windowSeconds: 300 },
      }),
    ),
    http.get('*/api/v1/auth/providers', () =>
      HttpResponse.json(
        paged([
          { id: 'local', kind: 'CREDENTIAL', label: 'Password', startPath: null },
          { id: 'acme:corp', kind: 'CREDENTIAL', label: 'Acme directory', startPath: null },
        ]),
      ),
    ),
  );

describe('PasswordSection', () => {
  it('offers a local account to change its password', async () => {
    serve('local');
    renderWithProviders(<PasswordSection />);

    expect(await screen.findByRole('link', { name: 'Change password' })).toBeInTheDocument();
  });

  it("tells a plugin sign-in's user where their password is kept instead of offering a form", async () => {
    serve('acme:corp');
    renderWithProviders(<PasswordSection />);

    expect(
      await screen.findByText('Your account signs in with Acme directory, which keeps your password. Change it there.'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Change password' })).not.toBeInTheDocument();
  });
});
