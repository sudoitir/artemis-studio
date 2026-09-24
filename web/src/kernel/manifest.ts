import { useMemo } from 'react';
import { useQuery, type UseQueryResult } from '@tanstack/react-query';

import { request, type ApiError } from './api/request.ts';
import type { components } from './api/schema.d.ts';
import type { ModuleId, StudioFeature } from './feature.ts';

export type ManifestView = components['schemas']['ManifestView'];
export type ManifestFeatureView = components['schemas']['ManifestFeatureView'];

export const manifestKey = ['manifest'] as const;

/**
 * What this installation offers (feature-modules spec). It describes and never authorizes: every
 * endpoint enforces its permission whatever a screen derived from this. Read once per page load:
 * plugin bundles are loaded before the router exists, so a plugin change needs a reload anyway,
 * and `usePluginsChanged` offers one when the server's `version` moves on.
 */
export function useManifest(): UseQueryResult<ManifestView, ApiError> {
  return useQuery({
    queryKey: manifestKey,
    queryFn: () => request<ManifestView>('/manifest'),
    staleTime: Infinity,
  });
}

/** The manifest's entry for a module; undefined until the manifest has loaded. */
export function useManifestFeature(id: ModuleId): ManifestFeatureView | undefined {
  return useManifest().data?.features.find((feature) => feature.id === id);
}

/**
 * The given features less those this installation has disabled. Until the manifest answers, every
 * feature counts as enabled: hiding a view before then would state something nobody has checked.
 */
export function useEnabledFeatures(features: StudioFeature[]): StudioFeature[] {
  const manifest = useManifest().data;
  // The same array for the same inputs: hooks that derive from the feature list (the current view,
  // the palette's sources) would otherwise see a new list on every render and re-run for nothing.
  return useMemo(
    () =>
      manifest
        ? features.filter((feature) => manifest.features.find((entry) => entry.id === feature.id)?.enabled !== false)
        : features,
    [features, manifest],
  );
}
