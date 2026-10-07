import { http, HttpResponse } from 'msw';

import { CONTRACT, FEATURE_IDS } from '../kernel/feature.ts';
import type { ManifestFeatureView, ManifestView } from '../kernel/manifest.ts';

/** An installed plugin's manifest entry, active and without a UI unless overridden. */
export function pluginEntry(id: string, overrides: Partial<ManifestFeatureView> = {}): ManifestFeatureView {
  return {
    id,
    title: id,
    kind: 'PLUGIN',
    enabled: true,
    enabledProperty: '',
    permissions: [],
    topics: [],
    origin: 'PLUGIN',
    version: '1.0.0',
    vendor: 'Acme',
    status: 'active',
    ...overrides,
  };
}

/**
 * A manifest for every module, with the named ones disabled (feature-modules spec), and any installed
 * plugins. A feature's title is its id, which is enough to find it by.
 *
 * Built from the ids alone, never from the feature list: the test setup installs the handler below
 * before a test file's module mocks apply, and importing the features here would load their
 * components unmocked.
 */
export function manifestView(
  disabled: string[] = [],
  {
    plugins = [],
    safeMode = false,
    version = '1',
  }: { plugins?: ManifestFeatureView[]; safeMode?: boolean; version?: string } = {},
): ManifestView {
  return {
    contract: CONTRACT,
    version,
    safeMode,
    features: [
      ...FEATURE_IDS.map((id) => ({
        id,
        title: id,
        kind: 'FEATURE' as const,
        enabled: !disabled.includes(id),
        enabledProperty: `artemis-studio.features.${id}.enabled`,
        permissions: [],
        topics: [],
        origin: 'BUILTIN' as const,
        version: null,
        vendor: null,
        status: null,
        ui: null,
      })),
      ...plugins,
    ],
    permissionCatalogue: [],
    identityProviders: [],
  };
}

/** A `/manifest` response of {@link manifestView}. */
export function manifestHandler(...args: Parameters<typeof manifestView>) {
  return http.get('*/api/v1/manifest', () => HttpResponse.json(manifestView(...args)));
}
