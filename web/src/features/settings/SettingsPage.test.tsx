import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { Notifications, notifications } from '@mantine/notifications';
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createRoute } from '@tanstack/react-router';

import { paged } from '../../kernel/api/paging.ts';
import { CONTRACT, defineFeature, type StudioFeature } from '../../kernel/feature.ts';
import { FeatureProvider } from '../../kernel/FeatureProvider.tsx';
import { rootRoute } from '../../kernel/routing/roots.ts';
import { manifestView, pluginEntry } from '../../test/manifest.ts';
import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { ChangePreview, Setting, SettingChange } from './api.ts';
import { SettingsPage, type SettingsSearch } from './SettingsPage.tsx';

/**
 * The Settings page (operator-ui spec): grouped tabs with one per settings category, one search across
 * them, and one draft of edits applied together, sent for approval or refused as a whole. Driven by a
 * feature list of its own, so the page is tested on what the slot hands it, not on which features happen
 * to be installed.
 */
const route = createRoute({
  getParentRoute: () => rootRoute,
  path: 'settings-under-test',
  // The harness provides the installed features to every slot; this page reads only the ones below.
  component: () => (
    <FeatureProvider features={features}>
      <Notifications />
      <SettingsPage />
    </FeatureProvider>
  ),
  validateSearch: (raw: Record<string, unknown>): SettingsSearch => ({
    ...(typeof raw.tab === 'string' && raw.tab ? { tab: raw.tab } : {}),
    ...(typeof raw.q === 'string' && raw.q ? { q: raw.q } : {}),
    ...(raw.modified === true || raw.modified === 'true' ? { modified: true } : {}),
  }),
});

const elsewhere = createRoute({
  getParentRoute: () => rootRoute,
  path: 'elsewhere',
  component: () => <p>Somewhere else</p>,
});

const section = (text: string) => () => <p>{text}</p>;

const features: StudioFeature[] = [
  defineFeature({
    contract: CONTRACT,
    id: 'settings',
    routes: { root: [route, elsewhere] },
    slots: {
      'settings.sections': [
        {
          id: 'credentials',
          order: 40,
          group: 'cluster',
          title: 'Broker credentials',
          Component: section('Rotate them'),
        },
        { id: 'display', order: 10, group: 'personal', title: 'Display', Component: section('Yours alone') },
        { id: 'health', order: 30, group: 'studio', title: 'Studio health', Component: section('Watching itself') },
        { id: 'from-a-plugin', order: 5, title: 'Plugin notes', Component: section('A plugin section') },
      ],
    },
  }),
];

function setting(over: Partial<Setting>): Setting {
  return {
    value: '5s',
    overridden: false,
    defaultValue: '5s',
    group: 'Polling',
    label: 'A setting',
    hint: 'What it does.',
    kind: 'DURATION',
    category: 'scrape',
    categoryTitle: 'Scrape',
    pending: [],
    ...over,
  };
}

function settings(over: Record<string, Partial<Setting>> = {}): Record<string, Setting> {
  const base: Record<string, Partial<Setting>> = {
    'scrape.tier-a': { label: 'Tier A interval', hint: 'HA state and topology.' },
    'scrape.tier-b': {
      label: 'Tier B interval',
      hint: 'Queue depths.',
      value: '60s',
      defaultValue: '30s',
      overridden: true,
    },
    'audit.batch': {
      label: 'Batch size',
      hint: 'Rows written at once.',
      kind: 'INT',
      value: '100',
      defaultValue: '100',
      group: 'Audit',
      category: 'audit',
      categoryTitle: 'Audit',
    },
    'mcp.read-only': {
      label: 'Read-only',
      hint: 'Refuses every mutating tool.',
      kind: 'BOOLEAN',
      value: 'false',
      defaultValue: 'false',
      group: 'Agent surface',
      category: 'mcp',
      categoryTitle: 'MCP server',
    },
    'acme.notes-limit': {
      label: 'Notes kept',
      hint: 'How many notes the plugin keeps.',
      kind: 'INT',
      value: '50',
      defaultValue: '50',
      group: 'Notes',
      category: 'acme',
      categoryTitle: 'Notes',
    },
  };
  return Object.fromEntries(Object.entries(base).map(([key, value]) => [key, setting({ ...value, ...over[key] })]));
}

const RUN: ChangePreview = { outcome: 'RUN', reasonRequired: false, fieldErrors: {} };

interface Recorded {
  applies: { changes: SettingChange[]; reason: string | null }[];
  previews: SettingChange[][];
}

/** Answers the settings, the preview and the apply as given, and records what the page sent. */
function serve({
  values = settings(),
  preview = () => RUN,
  apply = () => new HttpResponse(null, { status: 204 }),
}: {
  values?: Record<string, Setting> | (() => Record<string, Setting>);
  preview?: (changes: SettingChange[]) => ChangePreview;
  apply?: () => Response;
} = {}): Recorded {
  const recorded: Recorded = { applies: [], previews: [] };
  server.use(
    http.get('*/api/v1/settings', () =>
      HttpResponse.json({ settings: typeof values === 'function' ? values() : values }),
    ),
    http.post('*/api/v1/settings/changes/preview', async ({ request }) => {
      const { changes } = (await request.json()) as { changes: SettingChange[] };
      recorded.previews.push(changes);
      return HttpResponse.json(preview(changes));
    }),
    http.post('*/api/v1/settings/changes', async ({ request }) => {
      const { changes } = (await request.json()) as { changes: SettingChange[] };
      const reason = request.headers.get('X-Studio-Approval-Reason');
      recorded.applies.push({ changes, reason: reason === null ? null : decodeURIComponent(reason) });
      return apply();
    }),
  );
  return recorded;
}

/** The only tab listed, found by its whole accessible name. */
const onlyTab = (name: string) => {
  expect(within(screen.getByRole('tablist', { name: 'Settings sections' })).getAllByRole('tab')).toHaveLength(1);
  return screen.getByRole('tab', { name });
};

const footer = () => screen.getByRole('region', { name: 'Unsaved changes' });

describe('the Settings page', () => {
  // The shell gates on `/auth/me` and lists clusters, environments and firing alerts beside any page.
  beforeEach(() => {
    server.use(
      http.get('*/api/v1/auth/me', () =>
        HttpResponse.json({
          id: 'u1',
          username: 'ops',
          mustChangePassword: false,
          grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
        }),
      ),
      http.get('*/api/v1/clusters', () => HttpResponse.json(paged([]))),
      http.get('*/api/v1/environments', () => HttpResponse.json(paged([]))),
      http.get('*/api/v1/alerts/firing', () => HttpResponse.json(paged([]))),
      http.get('*/api/v1/manifest', () =>
        HttpResponse.json(manifestView([], { plugins: [pluginEntry('acme', { title: 'Acme notes' })] })),
      ),
    );
  });

  afterEach(() => act(() => notifications.clean()));

  it('lists a tab per settings category under Studio, and a plugin’s category under Plugins', async () => {
    serve();
    renderAppAt('/settings-under-test', features);

    const list = await screen.findByRole('tablist', { name: 'Settings sections' });
    await waitFor(() =>
      expect(list.textContent).toBe(
        'YoursDisplayStudioAuditMCP serverScrapeStudio healthThis clusterBroker credentialsPluginsAcme notesPlugin notes',
      ),
    );
    expect(screen.getByText('Yours alone')).toBeInTheDocument();
  });

  it('is one page: a single h1, and each open section is an h2', async () => {
    serve();
    renderAppAt('/settings-under-test?tab=scrape', features);

    await screen.findByLabelText('Tier A interval');
    expect(screen.getAllByRole('heading', { level: 1 }).map((h) => h.textContent)).toEqual(['Settings']);
    expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual(['Scrape']);
  });

  it('edits a category as a form, with its default and a reset beside a modified setting', async () => {
    serve();
    renderAppAt('/settings-under-test?tab=scrape', features);

    expect(await screen.findByLabelText('Tier A interval')).toHaveValue('5s');
    expect(screen.getByLabelText('Tier A interval')).toHaveAccessibleDescription(
      /A duration, such as 30s, 5m or 72h\./,
    );
    expect(screen.getByText('Modified · default 30s')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reset Tier B interval to its default, 30s' })).toBeInTheDocument();
  });

  it('opens the tab the address names, and keeps a chosen tab in the address', async () => {
    serve();
    const { router } = renderAppAt('/settings-under-test?tab=credentials', features);

    expect(await screen.findByText('Rotate them')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Broker credentials' })).toHaveAttribute('aria-selected', 'true');

    await userEvent.click(screen.getByRole('tab', { name: 'Audit' }));
    await waitFor(() => expect(router.state.location.search).toEqual({ tab: 'audit' }));
    expect(screen.getByLabelText('Batch size')).toHaveValue('100');
  });

  it('falls back to the first tab for an address naming none it has', async () => {
    serve();
    renderAppAt('/settings-under-test?tab=gone', features);

    expect(await screen.findByText('Yours alone')).toBeInTheDocument();
  });

  it('moves between tabs with the arrows and opens one with Enter, focusing its section', async () => {
    serve();
    renderAppAt('/settings-under-test', features);
    const user = userEvent.setup();

    const first = await screen.findByRole('tab', { name: 'Display' });
    await screen.findByRole('tab', { name: 'Audit' });
    first.focus();
    await user.keyboard('{ArrowDown}');
    expect(screen.getByRole('tab', { name: 'Audit' })).toHaveFocus();
    expect(screen.getByText('Yours alone')).toBeInTheDocument();

    await user.keyboard('{Enter}');
    const panel = await screen.findByRole('tabpanel', { name: 'Audit' });
    await waitFor(() => expect(panel).toHaveFocus());
    expect(within(panel).getByRole('heading', { level: 2, name: 'Audit' })).toBeInTheDocument();
  });

  it('searches every category, lists only those that match with their counts, and keeps the search in the address', async () => {
    serve();
    const { router } = renderAppAt('/settings-under-test', features);
    const user = userEvent.setup();

    await screen.findByRole('tab', { name: 'Audit' });
    await user.type(screen.getByRole('searchbox', { name: 'Search settings' }), 'interval');

    await waitFor(() => onlyTab('Scrape, 2 matching'));
    expect(router.state.location.search).toEqual({ q: 'interval' });
    expect(screen.getByLabelText('Tier A interval')).toBeInTheDocument();
    // The match is marked where it was found.
    expect(screen.getAllByText('interval', { selector: 'mark' }).length).toBeGreaterThan(0);

    await user.click(screen.getByRole('switch', { name: 'Modified only' }));
    await waitFor(() => onlyTab('Scrape, 1 matching'));
    expect(router.state.location.search).toEqual({ q: 'interval', modified: true });
    expect(screen.queryByLabelText('Tier A interval')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Tier B interval')).toBeInTheDocument();
  });

  it('finds a setting by its key, and says so when nothing matches, with a way back', async () => {
    serve();
    const { router } = renderAppAt('/settings-under-test?q=read-only', features);

    await waitFor(() => onlyTab('MCP server, 1 matching'));

    await act(() => router.navigate({ to: '/settings-under-test', search: { q: 'no-such-thing' } }));
    expect(await screen.findByText('No settings match “no-such-thing”')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Clear filters' }));

    await waitFor(() => expect(router.state.location.search).toEqual({}));
    expect(await screen.findByRole('tab', { name: 'Display' })).toBeInTheDocument();
    expect(screen.getByRole('searchbox', { name: 'Search settings' })).toHaveValue('');
  });

  it('keeps one draft across categories, marking each category that holds an edit', async () => {
    serve();
    renderAppAt('/settings-under-test?tab=scrape', features);
    const user = userEvent.setup();

    const tierA = await screen.findByLabelText('Tier A interval');
    await user.clear(tierA);
    await user.type(tierA, '10s');
    expect(screen.getByText('Edited · was 5s')).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'Audit' }));
    const batch = await screen.findByLabelText('Batch size');
    await user.clear(batch);
    await user.type(batch, '200');

    expect(within(footer()).getByText('2 unsaved changes in 2 categories')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Scrape, 1 unsaved change' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Audit, 1 unsaved change' })).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'Scrape, 1 unsaved change' }));
    expect(await screen.findByLabelText('Tier A interval')).toHaveValue('10s');

    await user.click(within(footer()).getByRole('button', { name: 'Discard' }));
    expect(screen.queryByRole('region', { name: 'Unsaved changes' })).not.toBeInTheDocument();
    expect(screen.getByLabelText('Tier A interval')).toHaveValue('5s');
    expect(screen.getByText('Discarded 2 unsaved changes.')).toBeInTheDocument();
  });

  it('asks before leaving with unsaved edits, and stays when told to', async () => {
    serve();
    const { router } = renderAppAt('/settings-under-test?tab=scrape', features);
    const user = userEvent.setup();

    const tierA = await screen.findByLabelText('Tier A interval');
    await user.type(tierA, '0');

    // Changing tab or search stays on the page and keeps the draft.
    await user.click(screen.getByRole('tab', { name: 'Audit' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

    void router.navigate({ to: '/elsewhere' });
    const dialog = await screen.findByRole('dialog', { name: 'Leave with unsaved changes?' });
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(router.state.location.pathname).toBe('/settings-under-test');

    void router.navigate({ to: '/elsewhere' });
    await user.click(
      within(await screen.findByRole('dialog', { name: 'Leave with unsaved changes?' })).getByRole('button', {
        name: 'Discard and leave',
      }),
    );
    expect(await screen.findByText('Somewhere else')).toBeInTheDocument();
  });

  it('applies the whole draft in one request when nothing needs approval', async () => {
    let applied = false;
    const recorded = serve({
      values: () =>
        applied
          ? settings({ 'scrape.tier-a': { value: '10s', overridden: true }, 'audit.batch': { value: '200' } })
          : settings(),
      apply: () => {
        applied = true;
        return new HttpResponse(null, { status: 204 });
      },
    });
    renderAppAt('/settings-under-test?tab=scrape', features);
    const user = userEvent.setup();

    const tierA = await screen.findByLabelText('Tier A interval');
    await user.clear(tierA);
    await user.type(tierA, '10s');
    await user.click(screen.getByRole('tab', { name: 'Audit' }));
    const batch = await screen.findByLabelText('Batch size');
    await user.clear(batch);
    await user.type(batch, '200');

    await waitFor(() => expect(within(footer()).getByText('Applies at once, with no restart.')).toBeInTheDocument());
    await user.click(within(footer()).getByRole('button', { name: 'Apply 2 changes' }));

    expect(await screen.findByText('Applied 2 setting changes')).toBeInTheDocument();
    expect(recorded.applies).toEqual([
      {
        changes: [
          { key: 'scrape.tier-a', value: '10s' },
          { key: 'audit.batch', value: '200' },
        ],
        reason: null,
      },
    ]);
    await waitFor(() => expect(screen.queryByRole('region', { name: 'Unsaved changes' })).not.toBeInTheDocument());
  });

  it('asks for approval with a reason when a policy holds the draft, then shows the changes as pending', async () => {
    let held = false;
    const pending = (value: string) => [
      { heldId: 'h-1', value, reset: false, requester: 'ops', requestedAt: new Date().toISOString() },
    ];
    const recorded = serve({
      values: () =>
        held
          ? settings({ 'scrape.tier-a': { pending: pending('10s') }, 'scrape.tier-b': { pending: pending('90s') } })
          : settings(),
      preview: () => ({ outcome: 'HOLD', reasonRequired: true, policyLabel: 'Two-person rule', fieldErrors: {} }),
      apply: () => {
        held = true;
        return HttpResponse.json(
          { heldOperation: { summary: 'Change 2 settings', expiresAt: '', link: '' } },
          { status: 202, headers: { 'X-Studio-Held-Operation': 'h-1' } },
        );
      },
    });
    renderAppAt('/settings-under-test?tab=scrape', features);
    const user = userEvent.setup();

    const tierA = await screen.findByLabelText('Tier A interval');
    await user.clear(tierA);
    await user.type(tierA, '10s');
    const tierB = screen.getByLabelText('Tier B interval');
    await user.clear(tierB);
    await user.type(tierB, '90s');

    const primary = await within(footer()).findByRole('button', { name: 'Request approval…' });
    expect(within(footer()).getByText('Needs approval under “Two-person rule”.')).toBeInTheDocument();
    await user.click(primary);

    const dialog = await screen.findByRole('dialog', { name: 'Request approval for 2 changes' });
    const rows = within(within(dialog).getByRole('table', { name: 'Changes to apply' })).getAllByRole('row');
    expect(rows.map((r) => r.textContent)).toEqual([
      'SettingCurrentNew',
      'Tier A interval (Scrape)5s10s',
      'Tier B interval (Scrape)60s90s',
    ]);
    expect(within(dialog).getByText(/Two-person rule/)).toBeInTheDocument();

    await user.click(within(dialog).getByRole('button', { name: 'Request approval' }));
    expect(await within(dialog).findByText(/Give a reason/)).toBeInTheDocument();
    expect(within(dialog).getByRole('textbox', { name: 'Reason' })).toHaveFocus();
    expect(recorded.applies).toEqual([]);

    await user.type(within(dialog).getByRole('textbox', { name: 'Reason' }), 'Brokers are overloaded, é');
    await user.click(within(dialog).getByRole('button', { name: 'Request approval' }));

    expect(await screen.findByText('Sent for approval')).toBeInTheDocument();
    expect(recorded.applies).toEqual([
      {
        changes: [
          { key: 'scrape.tier-a', value: '10s' },
          { key: 'scrape.tier-b', value: '90s' },
        ],
        reason: 'Brokers are overloaded, é',
      },
    ]);
    // The draft is gone, and each change waits beside its setting.
    await waitFor(() => expect(screen.getAllByText('Pending approval')).toHaveLength(2));
    expect(screen.queryByRole('region', { name: 'Unsaved changes' })).not.toBeInTheDocument();
    expect(screen.getByLabelText('Tier A interval')).toHaveValue('5s');
  });

  it('cannot apply a draft a policy would deny, and says why', async () => {
    serve({
      preview: () => ({
        outcome: 'DENY',
        reasonRequired: false,
        denyReason: 'Outside the change window.',
        fieldErrors: {},
      }),
    });
    renderAppAt('/settings-under-test?tab=mcp', features);
    const user = userEvent.setup();

    await user.click(await screen.findByRole('switch', { name: /Read-only/ }));

    expect(await within(footer()).findByText(/Outside the change window\./)).toBeInTheDocument();
    expect(within(footer()).getByRole('button', { name: 'Apply 1 change' })).toBeDisabled();
  });

  it('shows the server’s verdict on a value beside it once it loses focus, and focuses it on apply', async () => {
    const recorded = serve({
      preview: (changes): ChangePreview => ({
        outcome: undefined,
        reasonRequired: false,
        fieldErrors: Object.fromEntries(
          changes.filter((c) => c.key === 'scrape.tier-a').map((c) => [c.key, `${c.key} must be a positive duration`]),
        ),
      }),
    });
    renderAppAt('/settings-under-test?tab=scrape', features);
    const user = userEvent.setup();

    const tierA = await screen.findByLabelText('Tier A interval');
    await user.clear(tierA);
    await user.type(tierA, '-5s');
    await user.tab();

    expect(await screen.findByText('scrape.tier-a must be a positive duration')).toBeInTheDocument();
    expect(tierA).toHaveAccessibleDescription(/must be a positive duration/);

    await user.click(screen.getByRole('tab', { name: /Audit/ }));
    await user.click(within(footer()).getByRole('button', { name: 'Apply 1 change' }));
    await waitFor(() => expect(screen.getByLabelText('Tier A interval')).toHaveFocus());
    expect(recorded.applies).toEqual([]);
  });

  it('maps the server’s field errors from a refused apply onto their settings', async () => {
    serve({
      apply: () =>
        HttpResponse.json(
          { title: 'Invalid settings', errors: [{ field: 'audit.batch', message: 'audit.batch must be at least 1' }] },
          { status: 400 },
        ),
    });
    renderAppAt('/settings-under-test?tab=audit', features);
    const user = userEvent.setup();

    const batch = await screen.findByLabelText('Batch size');
    await user.clear(batch);
    await user.type(batch, '0');
    await waitFor(() => expect(within(footer()).getByText('Applies at once, with no restart.')).toBeInTheDocument());
    await user.click(within(footer()).getByRole('button', { name: 'Apply 1 change' }));

    expect(await screen.findByText('audit.batch must be at least 1')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByLabelText('Batch size')).toHaveFocus());
  });

  it('stages a reset to the default, and applies it as a reset', async () => {
    const recorded = serve();
    renderAppAt('/settings-under-test?tab=scrape', features);
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Reset Tier B interval to its default, 30s' }));
    expect(screen.getByLabelText('Tier B interval')).toHaveValue('30s');
    expect(screen.getByText('Resets to its default')).toBeInTheDocument();

    await waitFor(() => expect(within(footer()).getByText('Applies at once, with no restart.')).toBeInTheDocument());
    await user.click(within(footer()).getByRole('button', { name: 'Review' }));
    const dialog = await screen.findByRole('dialog', { name: 'Review 1 change' });
    expect(within(dialog).getByText('30s (default)')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Apply 1 change' }));

    await waitFor(() =>
      expect(recorded.applies).toEqual([{ changes: [{ key: 'scrape.tier-b', reset: true }], reason: null }]),
    );
  });

  it('lets the requester cancel a pending change, and not anyone else', async () => {
    let cancelled = '';
    serve({
      values: settings({
        'scrape.tier-a': {
          pending: [
            { heldId: 'h-mine', value: '10s', reset: false, requester: 'ops', requestedAt: new Date().toISOString() },
          ],
        },
        'scrape.tier-b': {
          pending: [{ heldId: 'h-theirs', reset: true, requester: 'kim', requestedAt: new Date().toISOString() }],
        },
      }),
    });
    server.use(
      http.post('*/api/v1/held-operations/:id/cancel', ({ params }) => {
        cancelled = String(params.id);
        return HttpResponse.json({ operation: { id: params.id } });
      }),
    );
    renderAppAt('/settings-under-test?tab=scrape', features);
    const user = userEvent.setup();

    const mine = await screen.findByRole('list', { name: 'Changes to Tier A interval waiting for approval' });
    expect(within(mine).getByText(/→ 10s · ops ·/)).toBeInTheDocument();
    const theirs = screen.getByRole('list', { name: 'Changes to Tier B interval waiting for approval' });
    expect(within(theirs).getByText(/→ its default, 30s · kim ·/)).toBeInTheDocument();
    expect(within(theirs).getByRole('link', { name: 'View request' })).toHaveAttribute('href', '/approvals/h-theirs');
    expect(within(theirs).queryByRole('button', { name: /Cancel request/ })).not.toBeInTheDocument();

    await user.click(within(mine).getByRole('button', { name: /Cancel request/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Cancel this request?' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel request' }));
    await waitFor(() => expect(cancelled).toBe('h-mine'));
  });

  it('shows why the settings could not be read, beside the sections that still work', async () => {
    server.use(http.get('*/api/v1/settings', () => HttpResponse.json({ title: 'Forbidden' }, { status: 403 })));
    renderAppAt('/settings-under-test?tab=configuration', features);

    const panel = await screen.findByRole('tabpanel', { name: 'Configuration' });
    expect(await within(panel).findByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Display' })).toBeInTheDocument();
  });
});
