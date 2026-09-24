import { afterEach, describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';

import { manifestHandler, pluginEntry } from '../../test/manifest.ts';
import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { setBootState } from './boot.ts';

const ADMIN = { id: 'u1', username: 'ops', mustChangePassword: false, grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }] };

describe('a plugin address with no page', () => {
  afterEach(() => setBootState({ plugins: [], failures: new Map() }));

  function signedIn() {
    server.use(http.get('*/api/v1/auth/me', () => HttpResponse.json(ADMIN)));
  }

  it('says when no such plugin is installed', async () => {
    signedIn();
    renderAppAt('/p/acme-ghost/anything');
    expect(await screen.findByRole('heading', { name: 'No plugin called acme-ghost is installed' })).toBeInTheDocument();
  });

  it('names the state that keeps a disabled plugin away, and links an administrator to it', async () => {
    signedIn();
    server.use(manifestHandler([], { plugins: [pluginEntry('acme-notes', { status: 'disabled', enabled: false, title: 'Notes' })] }));
    renderAppAt('/clusters/c1/p/acme-notes/notes');
    expect(await screen.findByRole('heading', { name: 'Notes is installed but disabled.' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'See Notes in Administration → Plugins' })).toHaveAttribute(
      'href',
      '/admin?tab=plugins&plugin=acme-notes',
    );
  });

  it('explains a running plugin whose screens failed to load, and offers a reload', async () => {
    signedIn();
    server.use(manifestHandler([], { plugins: [pluginEntry('acme-notes', { title: 'Notes' })] }));
    setBootState({ plugins: [], failures: new Map([['acme-notes', 'screens did not load: 404']]) });
    renderAppAt('/p/acme-notes');
    expect(await screen.findByRole('heading', { name: 'Notes is running, but its screens could not be shown' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reload the page' })).toBeInTheDocument();
  });

  it('says safe mode is why no plugin is running', async () => {
    signedIn();
    server.use(manifestHandler([], { safeMode: true, plugins: [pluginEntry('acme-notes')] }));
    renderAppAt('/p/acme-notes');
    expect(await screen.findByRole('heading', { name: /started in safe mode/ })).toBeInTheDocument();
  });
});
