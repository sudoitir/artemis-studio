import { afterEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';

import { manifestHandler, pluginEntry } from '../../test/manifest.ts';
import { server } from '../../test/setup.ts';
import { CONTRACT } from '../feature.ts';
import { boot, loadPlugins, REMOTE_TIMEOUT_MS, remoteName, setBootState } from './boot.ts';

const withUi = (id: string) => pluginEntry(id, { ui: { entry: `/plugin-ui/${id}/abcdef12/remoteEntry.js` } });

function federation(modules: Record<string, () => Promise<unknown>>) {
  const registered: string[] = [];
  return {
    registered,
    load: async () => ({
      registerRemotes: (remotes: { name: string }[]) => registered.push(...remotes.map((r) => r.name)),
      loadRemote: (id: string) => modules[id.split('/')[0]]() as never,
    }),
  };
}

describe('plugin boot', () => {
  afterEach(() => {
    vi.useRealTimers();
    setBootState({ plugins: [], failures: new Map() });
  });

  it('loads an active plugin with a UI and leaves the others alone', async () => {
    const fed = federation({ [remoteName('acme-notes')]: async () => ({ default: { contract: CONTRACT, id: 'acme-notes' } }) });
    const result = await loadPlugins(
      [withUi('acme-notes'), pluginEntry('acme-headless'), withUi('acme-off') && { ...withUi('acme-off'), status: 'disabled' }],
      fed.load as never,
    );
    expect(fed.registered).toEqual(['plugin_acme_notes']);
    expect(result.plugins.map((p) => p.id)).toEqual(['acme-notes']);
    expect(result.failures.size).toBe(0);
  });

  it('keeps going without a plugin whose bundle fails, is refused or hangs, and says why', async () => {
    vi.useFakeTimers();
    const fed = federation({
      [remoteName('acme-broken')]: () => Promise.reject(new Error('404 remoteEntry.js')),
      [remoteName('acme-wrong')]: async () => ({ default: { contract: CONTRACT, id: 'acme-other' } }),
      [remoteName('acme-slow')]: () => new Promise(() => {}),
      [remoteName('acme-good')]: async () => ({ default: { contract: CONTRACT, id: 'acme-good' } }),
    });
    const pending = loadPlugins(
      [withUi('acme-broken'), withUi('acme-wrong'), withUi('acme-slow'), withUi('acme-good')],
      fed.load as never,
    );
    await vi.advanceTimersByTimeAsync(REMOTE_TIMEOUT_MS + 1);
    const result = await pending;

    expect(result.plugins.map((p) => p.id)).toEqual(['acme-good']);
    expect(result.failures.get('acme-broken')).toMatch(/did not load: 404/);
    expect(result.failures.get('acme-wrong')).toMatch(/refused: its bundle calls itself/);
    expect(result.failures.get('acme-slow')).toMatch(/did not answer within 10 s/);
  });

  it('starts without plugins, and without complaint, when nobody is signed in', async () => {
    server.use(http.get('*/api/v1/manifest', () => new HttpResponse(null, { status: 401 })));
    const started = await boot();
    expect(started.manifest).toBeUndefined();
    expect(started.manifestError).toBeUndefined();
    expect(started.plugins).toEqual([]);
  });

  it('starts without plugins, and says so, when the manifest cannot be read', async () => {
    server.use(http.get('*/api/v1/manifest', () => new HttpResponse(null, { status: 503 })));
    const started = await boot();
    expect(started.manifestError).toMatch(/503/);
  });

  it('reads the manifest when signed in', async () => {
    server.use(manifestHandler([], { plugins: [pluginEntry('acme-headless')] }));
    const started = await boot();
    expect(started.manifest?.features.some((f) => f.id === 'acme-headless')).toBe(true);
  });
});
