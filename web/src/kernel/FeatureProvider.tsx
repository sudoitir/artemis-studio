import type { ReactNode } from 'react';

import type { StudioFeature } from './feature.ts';
import { FeaturesContext } from './features.ts';

/** Makes the composition root's features available to the kernel shell below it. */
export function FeatureProvider({ features, children }: { features: StudioFeature[]; children: ReactNode }) {
  return <FeaturesContext.Provider value={features}>{children}</FeaturesContext.Provider>;
}
