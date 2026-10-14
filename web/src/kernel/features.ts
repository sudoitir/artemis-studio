import { createContext, useContext } from 'react';

import type { RahdarFeature } from './feature.ts';
import { useEnabledFeatures } from './manifest.ts';

/** The composition root's features. The kernel never imports them; `FeatureProvider` hands them in (ADR-0069). */
export const FeaturesContext = createContext<RahdarFeature[] | null>(null);

/** The installed features this installation has enabled, in composition order. */
export function useFeatures(): RahdarFeature[] {
  const installed = useContext(FeaturesContext);
  const enabled = useEnabledFeatures(installed ?? []);
  if (!installed) {
    throw new Error('useFeatures needs a FeatureProvider above it');
  }
  return enabled;
}
