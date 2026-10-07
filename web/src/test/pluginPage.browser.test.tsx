import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { page as browserPage } from 'vitest/browser';
import { onlineManager, QueryClient } from '@tanstack/react-query';
import { createRoute } from '@tanstack/react-router';
import { screen } from '@testing-library/react';
import { Alert } from '@mantine/core';

import { CONTRACT, clusterRoute, definePlugin, pluginPath, pluginView } from '../sdk/index.ts';
import { renderApp } from './appShell.tsx';
import { SCHEMES } from './browser.tsx';
import { manifestView, pluginEntry } from './manifest.ts';
import { clusterKey } from '../kernel/api/request.ts';
import { accessKeys, keys as authKeys } from '../kernel/auth/api.ts';
import { accessFor } from './accessSummary.ts';
import { manifestKey } from '../kernel/manifest.ts';

/**
 * A plugin's page in the real shell, laid out by Chromium. A plugin owns the whole of its page, and a
 * page that opens with a notice is common; the host must put that notice below its fixed header and
 * beside the navigation, in the content area Studio's own pages use, with nothing covering it.
 */
beforeAll(() => onlineManager.setOnline(false));
afterAll(async () => {
  onlineManager.setOnline(true);
  await browserPage.viewport(1920, 1080);
});

/** The window at 100% and at 200% zoom: a 1280 x 800 window at 200% is 640 x 400 CSS px. */
const WINDOWS = [
  { zoom: '100%', width: 1280, height: 800 },
  { zoom: '200%', width: 640, height: 400 },
] as const;

const ID = 'acme-notes';
const AVAILABLE = { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null };

/** A plugin page that begins with a notice and has no page frame of its own, as a plugin author may write one. */
function NoticeFirst() {
  return (
    <>
      <Alert title="Heads up" color="yellow">
        This page begins with a notice.
      </Alert>
      <p>The rest of the page.</p>
    </>
  );
}

const notesRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: pluginPath(ID),
  component: pluginView(ID, NoticeFirst),
});
const plugin = definePlugin({ contract: CONTRACT, id: ID, routes: { cluster: [notesRoute] } });

function seeded(): QueryClient {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity, gcTime: Infinity } },
  });
  const grants = [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }];
  client.setQueryData(authKeys.me, { id: 'u1', username: 'admin', mustChangePassword: false, grants });
  client.setQueryData(accessKeys.of(), accessFor(grants));
  client.setQueryData(accessKeys.of('c1'), accessFor(grants, 'c1'));
  client.setQueryData(manifestKey, manifestView([], { plugins: [pluginEntry(ID)] }));
  client.setQueryData(clusterKey('c1'), {
    id: 'c1',
    name: 'prod',
    description: null,
    topology: { clusterId: 'c1', nodes: [] },
    capabilities: {
      managementRead: AVAILABLE,
      managementWrite: AVAILABLE,
      notifications: { status: 'UNKNOWN', reason: 'n/a', brokerXmlSnippet: null },
      messageIo: AVAILABLE,
      slowConsumerDetection: AVAILABLE,
      versionGates: [],
    },
    health: {
      clusterId: 'c1',
      level: 'OK',
      liveEndpointNames: [],
      splitBrain: 'NONE',
      replicationBehind: false,
      notes: [],
    },
  });
  return client;
}

describe.each(SCHEMES)('A plugin page that begins with a notice, in the %s scheme', (scheme) => {
  it.each(WINDOWS)('shows the whole notice below the header, beside the navigation, at $zoom zoom', async (w) => {
    await browserPage.viewport(w.width, w.height);
    renderApp(`/clusters/c1/p/${ID}`, seeded(), scheme, [plugin]);

    const alert = await screen.findByRole('alert');
    const header = document.querySelector('header')!;
    const navigation = document.querySelector('nav, [id="as-navbar"]')!;
    const box = alert.getBoundingClientRect();
    expect(box.top).toBeGreaterThanOrEqual(header.getBoundingClientRect().bottom);
    expect(box.bottom).toBeLessThanOrEqual(window.innerHeight);
    expect(box.left).toBeGreaterThanOrEqual(navigation.getBoundingClientRect().right);
    expect(box.right).toBeLessThanOrEqual(window.innerWidth);
    // Nothing the host draws sits over the notice: what is at its middle is the notice itself.
    const middle = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2);
    expect(alert.contains(middle)).toBe(true);
    // And the page is laid out as Studio's own are: it does not scroll to reach its first element.
    expect(window.scrollY).toBe(0);
  });
});
