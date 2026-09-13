import type { ReactNode } from 'react';
import { Loader } from '@mantine/core';
import { useParams } from '@tanstack/react-router';

import type { FeatureId } from '../feature.ts';
import { useManifest } from '../manifest.ts';
import { FeatureDisabled } from './FeatureDisabled.tsx';

/**
 * A feature's view, or the page that explains the feature is disabled on this installation
 * (feature-modules spec). It waits for the manifest rather than rendering a disabled view whose
 * every request would fail as not found. If the manifest cannot be read the view renders: the
 * server still refuses a disabled feature's calls, so nothing is exposed by trying.
 */
export function FeatureGate({ feature, children }: { feature: FeatureId; children: ReactNode }) {
  const manifest = useManifest();
  const { clusterId } = useParams({ strict: false }) as { clusterId?: string };

  if (manifest.isPending) return <Loader size="sm" />;
  const entry = manifest.data?.features.find((candidate) => candidate.id === feature);
  if (entry && !entry.enabled) {
    return <FeatureDisabled title={entry.title} property={entry.enabledProperty} clusterId={clusterId} />;
  }
  return <>{children}</>;
}
