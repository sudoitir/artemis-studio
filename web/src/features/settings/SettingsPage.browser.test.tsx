import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { page } from 'vitest/browser';
import { onlineManager, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
} from '@tanstack/react-router';

import { accessKeys, keys as authKeys } from '../../kernel/auth/api.ts';
import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { FeatureProvider } from '../../kernel/FeatureProvider.tsx';
import { manifestKey } from '../../kernel/manifest.ts';
import { axeViolations, contentWidth, Frame, renderThemed, SCHEMES, settle } from '../../test/browser.tsx';
import { manifestView, pluginEntry } from '../../test/manifest.ts';
import { previewQuery, SETTINGS_KEY, type ChangePreview, type Setting } from './api.ts';
import { SettingsPage } from './SettingsPage.tsx';

/**
 * The Settings page in a real browser at the narrowest window the console is laid out for (1280 px), in both
 * colour schemes and in each state an operator meets: a category, a search, nothing found, the settings
 * unreadable, a draft in the footer, a value the server rejects, a draft a policy denies, and the review of a
 * draft a policy holds. Each has no accessibility violations and scrolls nothing sideways.
 */
beforeAll(() => onlineManager.setOnline(false));
afterAll(() => onlineManager.setOnline(true));

const section = (text: string) => () => <p>{text}</p>;
const features = [
  defineFeature({
    contract: CONTRACT,
    id: 'settings',
    slots: {
      'settings.sections': [
        { id: 'display', order: 10, group: 'personal', title: 'Display', Component: section('Yours alone') },
        { id: 'security', order: 30, group: 'studio', title: 'Encryption keys', Component: section('Keys') },
        { id: 'health', order: 40, group: 'studio', title: 'Studio health', Component: section('Health') },
        { id: 'credentials', order: 50, group: 'cluster', title: 'Broker credentials', Component: section('Creds') },
      ],
    },
  }),
];

const ago = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString();

function s(over: Partial<Setting>): Setting {
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

const SETTINGS: Record<string, Setting> = {
  'scrape.tier-a': s({ label: 'Tier A interval', hint: 'HA state, topology and split-brain corroboration.' }),
  'scrape.tier-b': s({
    label: 'Tier B interval',
    hint: 'Queue depths, consumer counts and message rates.',
    value: '60s',
    defaultValue: '30s',
    overridden: true,
  }),
  'scrape.tier-c': s({
    label: 'Tier C interval',
    hint: 'Slow-moving facts: versions, acceptors, address settings.',
    value: '5m',
    defaultValue: '5m',
    pending: [{ heldId: 'h-1', value: '10m', reset: false, requester: 'kim', requestedAt: ago(14) }],
  }),
  'scrape.node-concurrency': s({
    label: 'Calls per node',
    hint: 'How many Jolokia calls Studio makes to one broker node at once.',
    kind: 'INT',
    value: '4',
    defaultValue: '4',
    group: 'Broker load',
    pending: [{ heldId: 'h-2', value: '8', reset: false, requester: 'ops', requestedAt: ago(3) }],
  }),
  'scrape.adaptive': s({
    label: 'Back off when a node is slow',
    hint: 'Doubles a node’s interval while its calls take longer than the interval itself.',
    kind: 'BOOLEAN',
    value: 'true',
    defaultValue: 'true',
    group: 'Broker load',
  }),
  'retention.metrics': s({
    label: 'Metric samples',
    hint: 'How long metric samples are kept before they are trimmed.',
    value: '7d',
    defaultValue: '7d',
    group: 'Retention',
    category: 'retention',
    categoryTitle: 'Retention',
  }),
  'retention.trim-cron': s({
    label: 'Trim schedule',
    hint: 'When old rows are deleted.',
    kind: 'CRON',
    value: '0 0 3 * * *',
    defaultValue: '0 0 3 * * *',
    group: 'Retention',
    category: 'retention',
    categoryTitle: 'Retention',
  }),
  'audit.batch': s({
    label: 'Batch size',
    hint: 'Rows written at once.',
    kind: 'INT',
    value: '100',
    defaultValue: '100',
    group: 'Audit',
    category: 'audit',
    categoryTitle: 'Audit',
  }),
  'mcp.read-only': s({
    label: 'Read-only',
    hint: 'Refuses every mutating tool, for every key.',
    kind: 'BOOLEAN',
    value: 'false',
    defaultValue: 'false',
    group: 'Agent surface',
    category: 'mcp',
    categoryTitle: 'MCP server',
  }),
  'apitokens.max-lifetime': s({
    label: 'Maximum lifetime',
    hint: 'No token lives longer than this from its creation. Lowering it shortens existing tokens at once.',
    value: '90d',
    defaultValue: '90d',
    group: 'API token lifetime',
    category: 'apitokens',
    categoryTitle: 'API tokens',
  }),
  'acme.notes-limit': s({
    label: 'Notes kept',
    hint: 'How many notes the plugin keeps.',
    kind: 'INT',
    value: '50',
    defaultValue: '50',
    group: 'Notes',
    category: 'acme',
    categoryTitle: 'Notes',
  }),
};

const EDITS = [
  { key: 'scrape.tier-a', value: '10s' },
  { key: 'scrape.tier-b', reset: true },
  { key: 'audit.batch', value: '200' },
];

function seeded(preview?: ChangePreview, broken = false): QueryClient {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity, gcTime: Infinity } },
  });
  client.setQueryData(authKeys.me, {
    id: 'u1',
    username: 'ops',
    mustChangePassword: false,
    grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
  });
  client.setQueryData(accessKeys.of(), { permissions: ['settings:read', 'settings:write'], teams: [] });
  client.setQueryData(manifestKey, manifestView([], { plugins: [pluginEntry('acme', { title: 'Acme notes' })] }));
  if (!broken) client.setQueryData(SETTINGS_KEY, { settings: SETTINGS });
  if (preview) {
    client.setQueryData(previewQuery([EDITS[0]]).queryKey, preview);
    client.setQueryData(previewQuery([EDITS[0], EDITS[1]]).queryKey, preview);
    client.setQueryData(previewQuery([EDITS[0], EDITS[1], EDITS[2]]).queryKey, preview);
  }
  return client;
}

function router(search: string) {
  const root = createRootRoute();
  const route = createRoute({
    getParentRoute: () => root,
    path: '/clusters/$clusterId/settings',
    component: () => (
      <FeatureProvider features={features}>
        <SettingsPage />
      </FeatureProvider>
    ),
    validateSearch: (raw: Record<string, unknown>) => ({
      ...(typeof raw.tab === 'string' ? { tab: raw.tab } : {}),
      ...(typeof raw.q === 'string' ? { q: raw.q } : {}),
      ...(raw.modified === true ? { modified: true } : {}),
    }),
  });
  return createRouter({
    routeTree: root.addChildren([route]),
    history: createMemoryHistory({ initialEntries: [`/clusters/c1/settings${search}`] }),
  });
}

async function edit() {
  const tierA = await screen.findByLabelText('Tier A interval');
  fireEvent.change(tierA, { target: { value: '10s' } });
  fireEvent.click(screen.getByRole('button', { name: /Reset Tier B interval/ }));
  fireEvent.click(screen.getByRole('link', { name: /Audit/ }));
  const batch = await screen.findByLabelText('Batch size');
  fireEvent.change(batch, { target: { value: '200' } });
  fireEvent.click(screen.getByRole('link', { name: /Scrape/ }));
  await screen.findByLabelText('Tier A interval');
}

const HOLD: ChangePreview = { outcome: 'HOLD', policyLabel: 'Two-person rule', fieldErrors: {} };

const STATES = [
  { name: 'category', search: '?tab=scrape' },
  { name: 'search', search: '?q=interval' },
  { name: 'empty-search', search: '?q=zzz' },
  { name: 'error', search: '?tab=configuration', broken: true },
  {
    name: 'dirty',
    search: '?tab=scrape',
    preview: { outcome: 'RUN', fieldErrors: {} },
    act: edit,
  },
  {
    name: 'invalid',
    search: '?tab=scrape',
    preview: {
      outcome: undefined,
      fieldErrors: { 'scrape.tier-a': 'scrape.tier-a must be at least 30s' },
    },
    act: async () => {
      await edit();
      fireEvent.blur(screen.getByLabelText('Tier A interval'));
    },
  },
  {
    name: 'deny',
    search: '?tab=scrape',
    preview: {
      outcome: 'DENY',
      denyReason: 'Changes are frozen until the release on Friday.',
      fieldErrors: {},
    },
    act: edit,
  },
  {
    name: 'review-hold',
    search: '?tab=scrape',
    preview: HOLD,
    act: async () => {
      await edit();
      fireEvent.click(await screen.findByRole('button', { name: 'Request approval…' }));
      await screen.findByRole('dialog');
    },
    dialog: true,
  },
] as const;

describe.each(SCHEMES)('the Settings page in the %s scheme at 1280 px', (scheme) => {
  it.each(STATES)('$name: no accessibility violations, nothing scrolls sideways', async (state) => {
    await page.viewport(1280, 900);
    const { container } = renderThemed(
      <QueryClientProvider
        client={seeded('preview' in state ? (state.preview as ChangePreview) : undefined, 'broken' in state)}
      >
        <Frame width={contentWidth(1280)} height={1800}>
          <RouterProvider router={router(state.search)} />
        </Frame>
      </QueryClientProvider>,
      scheme,
    );
    if ('broken' in state) onlineManager.setOnline(true);
    await screen.findByRole('heading', { level: 1, name: 'Settings' });
    if ('act' in state) await state.act();
    if ('broken' in state) await waitFor(() => within(container).getByRole('alert'), { timeout: 5000 });
    await settle(() => container.innerHTML.length.toString());
    // The draft is previewed once typing pauses (400 ms).
    await new Promise((r) => setTimeout(r, 600));
    await settle(() => container.innerHTML.length.toString());
    const scope = 'dialog' in state ? document.querySelector('.mantine-Modal-content')! : container;
    expect(await axeViolations(scope)).toEqual([]);
    expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(1280);
    if ('broken' in state) onlineManager.setOnline(false);
  });
});
