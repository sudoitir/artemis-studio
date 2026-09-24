import { CONTRACT, type StudioFeature } from '../feature.ts';
import type { ManifestFeatureView, ManifestView } from '../manifest.ts';
import { checkPlugin } from './validate.ts';

/** How long the shell waits for the manifest, and for each plugin bundle, before going on without it. */
export const MANIFEST_TIMEOUT_MS = 5_000;
export const REMOTE_TIMEOUT_MS = 10_000;

/** What the page learned while starting: the manifest (when it could be read) and every plugin that did not load. */
export interface Boot {
  manifest?: ManifestView;
  /** Why the manifest could not be read; the built-in screens load regardless. */
  manifestError?: string;
  plugins: StudioFeature[];
  /** Plugin id → why its screens are not here. */
  failures: Map<string, string>;
}

/**
 * Read once, when the page starts (ADR-0100), and then never changed: the router is built from
 * it. The catch-all page a plugin's address falls back to reads it to say why.
 */
let current: Boot = { plugins: [], failures: new Map() };

export function bootState(): Boot {
  return current;
}

/** For tests. */
export function setBootState(boot: Boot): void {
  current = boot;
}

function withTimeout<T>(promise: Promise<T>, ms: number, what: string): Promise<T> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`${what} did not answer within ${ms / 1000} s`)), ms);
    promise.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (error: unknown) => {
        clearTimeout(timer);
        reject(error);
      },
    );
  });
}

/** The Module Federation container name a plugin's bundle is built with: its id, in snake_case. */
export function remoteName(id: string): string {
  return `plugin_${id.replace(/-/g, '_')}`;
}

/**
 * The manifest, fetched without the shared `request()`: before sign-in the answer is 401, and
 * `request()` would navigate to the login page from under a page that has not rendered yet.
 */
async function fetchManifest(): Promise<{ manifest?: ManifestView; error?: string }> {
  try {
    const response = await withTimeout(
      fetch('/api/v1/manifest', { credentials: 'same-origin', headers: { accept: 'application/json' } }),
      MANIFEST_TIMEOUT_MS,
      'The server',
    );
    if (response.status === 401) return {}; // not signed in: the login page needs no plugins
    if (response.status === 423) return {}; // password change pending: that page needs no plugins either
    if (!response.ok) return { error: `the server answered ${response.status}` };
    return { manifest: (await response.json()) as ManifestView };
  } catch (error) {
    return { error: error instanceof Error ? error.message : String(error) };
  }
}

type Federation = Pick<typeof import('@module-federation/runtime'), 'registerRemotes' | 'loadRemote'>;

/**
 * Loads every active plugin's bundle, each bounded by {@link REMOTE_TIMEOUT_MS}, and keeps the
 * ones that pass {@link checkPlugin}. A bundle that fails, is slow or breaks the rules is left
 * out with its reason; nothing a plugin does here can keep Studio's own screens from loading.
 */
export async function loadPlugins(
  entries: ManifestFeatureView[],
  federation: () => Promise<Federation> = () => import('@module-federation/runtime'),
): Promise<Pick<Boot, 'plugins' | 'failures'>> {
  const failures = new Map<string, string>();
  const withUi = entries.filter((entry) => entry.origin === 'PLUGIN' && entry.status === 'active' && entry.ui?.entry);
  if (withUi.length === 0) return { plugins: [], failures };

  const { registerRemotes, loadRemote } = await federation();
  registerRemotes(withUi.map((entry) => ({ name: remoteName(entry.id), entry: entry.ui!.entry, type: 'module' })));

  const settled = await Promise.allSettled(
    withUi.map((entry) =>
      withTimeout(
        loadRemote<{ default?: unknown }>(`${remoteName(entry.id)}/feature`),
        REMOTE_TIMEOUT_MS,
        `The ${entry.id} bundle`,
      ).then((module) => checkPlugin(entry, module?.default)),
    ),
  );
  const plugins: StudioFeature[] = [];
  settled.forEach((result, index) => {
    const id = withUi[index].id;
    if (result.status === 'rejected') {
      failures.set(id, `its screens did not load: ${result.reason instanceof Error ? result.reason.message : String(result.reason)}`);
    } else if (!result.value.ok) {
      failures.set(id, `its screens were refused: ${result.value.reason}`);
    } else {
      plugins.push(result.value.feature);
    }
  });
  return { plugins, failures };
}

/** Everything the page needs before it builds its router: the manifest, then the plugins it names. */
export async function boot(): Promise<Boot> {
  const { manifest, error } = await fetchManifest();
  const loaded = manifest ? await loadPlugins(manifest.features) : { plugins: [], failures: new Map<string, string>() };
  if (manifest && manifest.contract !== CONTRACT) {
    console.warn(`The server speaks extension contract ${manifest.contract}; this page was built for ${CONTRACT}.`);
  }
  current = { manifest, manifestError: error, ...loaded };
  return current;
}
