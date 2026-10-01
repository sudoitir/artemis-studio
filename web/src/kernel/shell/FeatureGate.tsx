import type { ReactNode } from 'react';
import { useParams } from '@tanstack/react-router';

import { LoadingState } from '../../ui/LoadingState.tsx';
import type { ModuleId } from '../feature.ts';
import { useManifest } from '../manifest.ts';
import { PluginUnavailable } from '../plugins/PluginUnavailable.tsx';
import { FeatureDisabled } from './FeatureDisabled.tsx';

/** The height a view holds while the manifest loads, so the page does not jump when the view arrives. */
const VIEW_BLOCK_SIZE = '24rem';

/**
 * A feature's view, or the page that explains the feature is disabled on this installation
 * (feature-modules spec). It waits for the manifest rather than rendering a disabled view whose
 * every request would fail as not found. If the manifest cannot be read the view renders: the
 * server still refuses a disabled feature's calls, so nothing is exposed by trying.
 */
export function FeatureGate({ feature, children }: Readonly<{ feature: ModuleId; children: ReactNode }>) {
  const manifest = useManifest();
  const { clusterId } = useParams({ strict: false }) as { clusterId?: string };

  if (manifest.isPending) return <LoadingState label="Loading the page" blockSize={VIEW_BLOCK_SIZE} />;
  const entry = manifest.data?.features.find((candidate) => candidate.id === feature);
  if (entry && !entry.enabled && entry.origin === 'PLUGIN') {
    return <PluginUnavailable />;
  }
  if (entry && !entry.enabled) {
    return <FeatureDisabled title={entry.title} property={entry.enabledProperty} clusterId={clusterId} />;
  }
  return <>{children}</>;
}
