import { afterEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryHistory, createRootRoute, createRoute, createRouter, RouterProvider } from '@tanstack/react-router';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { PluginPlanView, PluginsView, PluginView } from './api.ts';
import { PluginsPanel } from './PluginsPanel.tsx';

const NOW = new Date().toISOString();

const INFO = {
  name: 'acme-notes',
  title: 'Notes',
  description: 'Shared notes on queues.',
  vendor: { name: 'Acme', url: 'https://acme.example', email: null },
  license: 'Apache-2.0',
  changeNotes: null,
  since: '2026.01.0',
  until: null,
  restartToActivate: false,
  updateUrl: null,
  requires: [],
  contributions: {
    ui: true,
    permissions: [{ action: 'acme-notes:write', description: 'Write notes' }],
    settingKeys: [],
    streamTopics: [],
    mcpTools: [{ name: 'acme_notes_search', posture: 'read', description: null }],
  },
};

function plugin(overrides: Partial<PluginView> = {}): PluginView {
  return {
    id: 'acme-notes',
    version: '1.0.0',
    status: 'active',
    failure: null,
    progress: null,
    stepStartedAt: null,
    installedAt: NOW,
    activatedAt: NOW,
    installedBy: 'ops',
    sha256: 'a'.repeat(64),
    rollbackAvailable: false,
    stuck: false,
    iconUrl: null,
    dependants: [],
    info: INFO,
    ...overrides,
  } as PluginView;
}

function inventory(plugins: PluginView[], overrides: Partial<PluginsView> = {}): PluginsView {
  return {
    canInstall: true,
    cannotInstall: null,
    uploadEnabled: true,
    safeMode: false,
    safeModeReason: null,
    budget: { maxConnections: 100, inUse: 13, limit: 80, perPlugin: 3 },
    restart: { supervised: true, needed: false, restarting: false, allowedAt: null, command: 'docker compose restart studio', unreleased: [] },
    plugins,
    ...overrides,
  } as PluginsView;
}

const PLAN: PluginPlanView = {
  pluginId: 'acme-notes',
  fromVersion: null,
  toVersion: '1.0.0',
  activationClass: 'BRIEF_MAINTENANCE',
  pendingChangesets: [{ id: '0001', author: 'acme', reversible: false }],
  updateSql: 'CREATE TABLE note (id uuid);',
  reversible: false,
  diff: {
    permissionsAdded: [], permissionsRemoved: [], settingKeysAdded: [], settingKeysRemoved: [],
    streamTopicsAdded: [], streamTopicsRemoved: [], mcpToolsAdded: [], mcpToolsRemoved: [],
  },
  rolesLosingPermission: {},
  compatible: true,
  missingRequires: [],
  restart: 'NONE',
  info: INFO,
} as PluginPlanView;

function me(authenticatedAt: string | null = NOW) {
  return http.get('*/api/v1/auth/me', () =>
    HttpResponse.json({
      id: 'u1',
      username: 'ops',
      mustChangePassword: false,
      grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
      reauthentication: { method: 'PASSWORD', startPath: null, authenticatedAt, windowSeconds: 300 },
    }),
  );
}

function renderPanel(path = '/admin?tab=plugins') {
  const rootRoute = createRootRoute();
  const admin = createRoute({
    getParentRoute: () => rootRoute,
    path: 'admin',
    component: PluginsPanel,
    validateSearch: (raw: Record<string, unknown>) => raw as { plugin?: string; upload?: string },
  });
  const router = createRouter({
    routeTree: rootRoute.addChildren([admin]),
    history: createMemoryHistory({ initialEntries: [path] }),
  });
  return renderWithProviders(<RouterProvider router={router} />);
}

afterEach(() => vi.restoreAllMocks());

describe('Administration → Plugins', () => {
  it('teaches what plugins are when there are none, and where to start', async () => {
    server.use(me(), http.get('*/api/v1/admin/plugins', () => HttpResponse.json(inventory([]))));
    renderPanel();
    expect(await screen.findByText('No plugins yet')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Build a plugin →' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Install plugin…' })).toBeEnabled();
  });

  it('keeps install visible but disabled, with the reason, for someone who is not an installer', async () => {
    server.use(
      me(),
      http.get('*/api/v1/admin/plugins', () =>
        HttpResponse.json(
          inventory([], {
            canInstall: false,
            cannotInstall: { code: 'plugin-installer-required', message: 'Only someone who can install plugins can do this.' },
          }),
        ),
      ),
    );
    renderPanel();
    expect(await screen.findByRole('button', { name: 'Install plugin…' })).toBeDisabled();
    expect(screen.getByText('Only someone who can install plugins can do this.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Who can install' })).toBeNull();
  });

  it('says so when installing is switched off', async () => {
    server.use(me(), http.get('*/api/v1/admin/plugins', () => HttpResponse.json(inventory([], { uploadEnabled: false }))));
    renderPanel();
    expect(await screen.findByText(/switched off on this installation/)).toBeInTheDocument();
  });

  it('puts a failed plugin first, in words, and summarises what needs attention', async () => {
    server.use(
      me(),
      http.get('*/api/v1/admin/plugins', () =>
        HttpResponse.json(
          inventory([
            plugin({ id: 'acme-alpha', info: { ...INFO, title: 'Alpha' } }),
            plugin({ id: 'acme-zeta', status: 'failed', failure: 'boom', info: { ...INFO, title: 'Zeta' } }),
          ]),
        ),
      ),
    );
    renderPanel();
    expect(await screen.findByRole('status')).toHaveTextContent('1 plugin needs attention: Zeta (failed)');
    const rows = screen.getAllByRole('row').slice(1);
    expect(rows[0]).toHaveTextContent('Zeta');
    expect(rows[0]).toHaveTextContent('Failed');
    expect(within(rows[0]).getByRole('button', { name: 'See why' })).toBeInTheDocument();
  });

  it('lists everything wrong with a jar at once, and stores nothing', async () => {
    server.use(
      me(),
      http.get('*/api/v1/admin/plugins', () => HttpResponse.json(inventory([]))),
      http.put('*/api/v1/admin/plugins/upload', () =>
        HttpResponse.json(
          {
            type: 'https://artemis-studio.dev/problems/plugin-refused',
            title: 'Refused',
            status: 422,
            detail: 'refused',
            violations: [
              { code: 'manifest-attribute', message: 'Class-Path is not allowed.', fix: 'Shade dependencies instead.', severity: 'ERROR' },
              { code: 'denied-call', message: 'Calls System.exit.', fix: 'Remove the call.', severity: 'ERROR' },
            ],
          },
          { status: 422 },
        ),
      ),
    );
    const user = userEvent.setup();
    const { container } = renderPanel();
    await screen.findByText('No plugins yet');
    const input = container.ownerDocument.querySelector('input[type="file"]') as HTMLInputElement;
    await user.upload(input, new File(['PK'], 'acme-notes-1.0.0.jar', { type: 'application/java-archive' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Class-Path is not allowed.');
    expect(alert).toHaveTextContent('Calls System.exit.');
    expect(alert).toHaveTextContent('Nothing was stored.');
    expect(within(alert).getByRole('button', { name: 'Copy report for the plugin author' })).toBeInTheDocument();
  });

  it('installs through inspect, review and a typed confirmation, then follows it to active', async () => {
    let status: 'activating' | 'active' = 'activating';
    let activated = false;
    server.use(
      me(),
      http.get('*/api/v1/admin/plugins', () =>
        HttpResponse.json(
          inventory(activated ? [plugin({ status, progress: status === 'activating' ? 'migrating' : null, activatedAt: new Date().toISOString() })] : []),
        ),
      ),
      http.put('*/api/v1/admin/plugins/upload', () =>
        HttpResponse.json({ sha256: 'b'.repeat(64), plan: PLAN, warnings: [] }, { status: 201 }),
      ),
      http.post('*/api/v1/admin/plugins/uploads/:sha/activate', () => {
        activated = true;
        return HttpResponse.json(PLAN, { status: 202 });
      }),
    );
    const user = userEvent.setup();
    const { container } = renderPanel();
    await screen.findByText('No plugins yet');
    await user.upload(
      container.ownerDocument.querySelector('input[type="file"]') as HTMLInputElement,
      new File(['PK'], 'acme-notes-1.0.0.jar'),
    );

    // Review: capabilities as sentences, the pause, and the irreversible change, before anything is armed.
    expect(await screen.findByText(/Give the assistant 1 read-only tool/)).toBeInTheDocument();
    expect(screen.getByText(/Its database schema is created, then it starts/)).toBeInTheDocument();
    expect(screen.getByText(/Irreversible/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Show the SQL' }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    const confirm = screen.getByRole('button', { name: 'Install Notes 1.0.0 (1 database change)' });
    expect(confirm).toBeDisabled();
    await user.type(screen.getByLabelText('Type "acme-notes" to confirm'), 'acme-notes');
    await user.click(confirm);

    expect(await screen.findByText(/You can close this; it carries on/)).toBeInTheDocument();
    status = 'active';
    expect(await screen.findByText('Notes 1.0.0 is active', {}, { timeout: 5_000 })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reload Studio' })).toBeInTheDocument();
  });

  it('asks a session that signed in long ago to confirm it is them before it can install', async () => {
    let reauthenticated = false;
    server.use(
      me(new Date(Date.now() - 10 * 60_000).toISOString()),
      http.get('*/api/v1/admin/plugins', () => HttpResponse.json(inventory([]))),
      http.put('*/api/v1/admin/plugins/upload', () =>
        HttpResponse.json({ sha256: 'b'.repeat(64), plan: { ...PLAN, activationClass: 'INSTANT', pendingChangesets: [], updateSql: '' }, warnings: [] }, { status: 201 }),
      ),
      http.post('*/api/v1/auth/reauthenticate', async () => {
        reauthenticated = true;
        return HttpResponse.json({ method: 'PASSWORD', startPath: null, authenticatedAt: new Date().toISOString(), windowSeconds: 300 });
      }),
    );
    const user = userEvent.setup();
    const { container } = renderPanel();
    await screen.findByText('No plugins yet');
    await user.upload(container.ownerDocument.querySelector('input[type="file"]') as HTMLInputElement, new File(['PK'], 'n.jar'));
    await user.click(await screen.findByRole('button', { name: 'Continue' }));

    expect(screen.getByText('Confirm it is you')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Type "acme-notes" to confirm'), 'acme-notes');
    expect(screen.getByRole('button', { name: 'Install Notes 1.0.0' })).toBeDisabled();

    await user.click(screen.getByRole('button', { name: 'Confirm' }));
    expect(await screen.findByText('Enter your password.')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Your password'), 'secret');
    await user.click(screen.getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(reauthenticated).toBe(true));
  });

  it('purges an uninstalled plugin only after its reach is shown and its id typed, by keyboard alone', async () => {
    let purged = false;
    server.use(
      me(),
      http.get('*/api/v1/admin/plugins', () => HttpResponse.json(inventory([plugin({ status: 'uninstalled', activatedAt: null })]))),
      http.get('*/api/v1/admin/plugins/acme-notes/history', () => HttpResponse.json([])),
      http.post('*/api/v1/admin/plugins/acme-notes/purge', ({ request }) => {
        if (new URL(request.url).searchParams.get('dryRun') === 'false') purged = true;
        return HttpResponse.json({
          schema: 'plugin_acme_notes',
          tables: [{ name: 'note', estimatedRows: 1200, bytes: 65536 }],
          grants: 2,
          settings: 0,
          artifacts: 1,
        });
      }),
    );
    const user = userEvent.setup();
    renderPanel('/admin?tab=plugins&plugin=acme-notes');

    await user.click(await screen.findByRole('tab', { name: 'Actions' }));
    const trigger = screen.getByRole('button', { name: 'Purge its data…' });
    trigger.focus();
    await user.keyboard('{Enter}');

    const dialog = await screen.findByRole('dialog', { name: "Purge Notes's data" });
    expect(await within(dialog).findByText(/about 1,200 rows/)).toBeInTheDocument();
    expect(within(dialog).getByText('2 role grants of its permissions')).toBeInTheDocument();
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog', { name: "Purge Notes's data" })).toBeNull());
    await waitFor(() => expect(trigger).toHaveFocus());

    await user.keyboard('{Enter}');
    const again = await screen.findByRole('dialog', { name: "Purge Notes's data" });
    await user.type(within(again).getByLabelText('Type "acme-notes" to confirm'), 'acme-notes');
    await user.tab();
    await user.keyboard('{Enter}');
    await waitFor(() => expect(purged).toBe(true));
  });

  it('offers a restart when Studio can make one, and the command when it cannot', async () => {
    const waiting = plugin({ status: 'needs_restart', failure: 'Restart Studio to start acme-notes 1.1.0.' });
    server.use(
      me(),
      http.get('*/api/v1/admin/plugins', () =>
        HttpResponse.json(inventory([waiting], { restart: { ...inventory([]).restart, needed: true } })),
      ),
    );
    const first = renderPanel();
    expect(await screen.findByRole('button', { name: 'Restart Studio…' })).toBeEnabled();
    expect(screen.getByText('Notes starts after a restart.')).toBeInTheDocument();
    first.unmount();

    server.use(
      http.get('*/api/v1/admin/plugins', () =>
        HttpResponse.json(inventory([waiting], { restart: { ...inventory([]).restart, needed: true, supervised: false } })),
      ),
    );
    renderPanel();
    expect(await screen.findByText('docker compose restart studio')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Restart Studio…' })).toBeNull();
  });

  it('shows what a drop will do before anything is installed', async () => {
    server.use(me(), http.get('*/api/v1/admin/plugins', () => HttpResponse.json(inventory([]))));
    renderPanel();
    const empty = await screen.findByText('No plugins yet');
    fireEvent.dragOver(empty);
    expect(screen.getByText('Drop to inspect. Nothing is installed until you confirm.')).toBeInTheDocument();
  });
});
