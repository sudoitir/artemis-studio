import type { ReactNode } from 'react';

import type { RahdarFeature } from './feature.ts';
import { FeaturesContext } from './features.ts';

/** Makes the composition root's features available to the kernel shell below it. */
export function FeatureProvider({ features, children }: Readonly<{ features: RahdarFeature[]; children: ReactNode }>) {
  return <FeaturesContext.Provider value={features}>{children}</FeaturesContext.Provider>;
}
