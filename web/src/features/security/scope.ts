import { useClusters, useEnvironments } from '../clusters/index.ts';
import type { ScopeLabel } from './words.ts';

/** Where a grant applies: everywhere, in one environment, or on one cluster. */
export type ScopeType = 'GLOBAL' | 'ENVIRONMENT' | 'CLUSTER';

export interface GrantScope {
  scopeType: ScopeType;
  scopeId: string | null;
}

export const GLOBAL_SCOPE: GrantScope = { scopeType: 'GLOBAL', scopeId: null };

/** What a form says about a scope that is not complete: the thing to choose, or nothing when it is. */
export function scopeError(scope: GrantScope): string | null {
  if (scope.scopeType === 'GLOBAL' || scope.scopeId) return null;
  return scope.scopeType === 'ENVIRONMENT'
    ? 'Choose the environment the role applies to.'
    : 'Choose the cluster the role applies to.';
}

/** The request fields for a scope: a global grant carries no id. */
export const scopeBody = (scope: GrantScope) =>
  scope.scopeType === 'GLOBAL' ? { scopeType: 'GLOBAL' } : { scopeType: scope.scopeType, scopeId: scope.scopeId };

/**
 * A grant's scope in words, with the name of the environment or cluster: "Global", "Environment Production".
 * A name that cannot be found (it was deleted, or the lists have not loaded) is said so, never left blank.
 */
export function useScopeLabel(): ScopeLabel {
  const environments = useEnvironments();
  const clusters = useClusters();
  return (scopeType, scopeId) => {
    if (scopeType === 'GLOBAL') return 'Global';
    const word = scopeType === 'ENVIRONMENT' ? 'Environment' : 'Cluster';
    const named =
      scopeType === 'ENVIRONMENT'
        ? environments.data?.find((e) => e.id === scopeId)
        : clusters.data?.find((c) => c.id === scopeId);
    return named ? `${word} ${named.name}` : `${word} ${scopeId ? 'not found' : ''}`.trim();
  };
}
