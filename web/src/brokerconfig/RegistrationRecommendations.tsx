import type { ConfigRecommendationsView } from '../api/client.ts';
import { RecommendedConfiguration } from './RecommendedConfiguration.tsx';

/**
 * The recommended configuration below a passing registration check
 * (`cluster.registration.afterProbe`): the same panel the cluster's Recommended tab shows, seeded
 * from the node the check reached, so the operator sees the actual entries rather than a generic
 * snippet. It cannot declare yet: there is no cluster for a revision to belong to.
 */
export function RegistrationRecommendations({ contributions }: { contributions: Record<string, unknown> }) {
  const recommendations = contributions.brokerconfig as ConfigRecommendationsView | undefined;
  if (!recommendations?.recommendations.some((r) => r.appliable)) return null;
  return <RecommendedConfiguration recommendations={recommendations} />;
}
