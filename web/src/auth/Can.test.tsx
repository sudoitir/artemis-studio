import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';

import { renderWithProviders } from '../test/render.tsx';
import { server } from '../test/setup.ts';
import { Can } from './Can.tsx';

function mockMe(grants: { scopeType: string; scopeId: string | null; permissions: string[] }[]) {
  server.use(
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({ id: 'u1', username: 'u', mustChangePassword: false, grants }),
    ),
  );
}

describe('Can', () => {
  it('renders children when the global grant covers the permission', async () => {
    mockMe([{ scopeType: 'GLOBAL', scopeId: null, permissions: ['user:admin'] }]);
    renderWithProviders(
      <Can permission="user:admin">
        <span>secret</span>
      </Can>,
    );
    expect(await screen.findByText('secret')).toBeInTheDocument();
  });

  it('renders the fallback when no grant covers the permission', async () => {
    mockMe([{ scopeType: 'GLOBAL', scopeId: null, permissions: ['cluster:read'] }]);
    renderWithProviders(
      <Can permission="user:admin" fallback={<span>hidden</span>}>
        <span>secret</span>
      </Can>,
    );
    expect(await screen.findByText('hidden')).toBeInTheDocument();
    expect(screen.queryByText('secret')).not.toBeInTheDocument();
  });

  it('a cluster-scoped grant only covers its own cluster', async () => {
    mockMe([{ scopeType: 'CLUSTER', scopeId: 'c1', permissions: ['message:send'] }]);
    renderWithProviders(
      <>
        <Can permission="message:send" clusterId="c1">
          <span>c1-ok</span>
        </Can>
        <Can permission="message:send" clusterId="c2" fallback={<span>c2-hidden</span>}>
          <span>c2-ok</span>
        </Can>
      </>,
    );
    expect(await screen.findByText('c1-ok')).toBeInTheDocument();
    expect(await screen.findByText('c2-hidden')).toBeInTheDocument();
  });

  it('a resource wildcard covers every verb on that resource', async () => {
    mockMe([{ scopeType: 'GLOBAL', scopeId: null, permissions: ['message:*'] }]);
    renderWithProviders(
      <Can permission="message:delete">
        <span>secret</span>
      </Can>,
    );
    expect(await screen.findByText('secret')).toBeInTheDocument();
  });
});
