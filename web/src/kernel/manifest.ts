import { useQuery, type UseQueryResult } from '@tanstack/react-query';

import { request, type ApiError } from '../api/client.ts';
import type { components } from '../api/schema.d.ts';
import type { FeatureId, StudioFeature } from './feature.ts';

export type ManifestView = components['schemas']['ManifestView'];
export type ManifestFeatureView = components['schemas']['ManifestFeatureView'];

export const manifestKey = ['manifest'] as const;

/**
 * What this installation offers (feature-modules spec). It describes and never authorizes: every
 * endpoint enforces its permission whatever a screen derived from this. It changes only on a
 * restart, so it is read once per page load.
 */
export function useManifest(): UseQueryResult<ManifestView, ApiError> {
  return useQuery({
    queryKey: manifestKey,
    queryFn: () => request<ManifestView>('/manifest'),
    staleTime: Infinity,
  });
}

/** The manifest's entry for a module; undefined until the manifest has loaded. */
export function useManifestFeature(id: FeatureId): ManifestFeatureView | undefined {
  return useManifest().data?.features.find((feature) => feature.id === id);
}

/**
 * The given features less those this installation has disabled. Until the manifest answers, every
 * feature counts as enabled: hiding a view before then would state something nobody has checked.
 */
export function useEnabledFeatures(features: StudioFeature[]): StudioFeature[] {
  const manifest = useManifest().data;
  if (!manifest) return features;
  return features.filter(
    (feature) => manifest.features.find((entry) => entry.id === feature.id)?.enabled !== false,
  );
}
