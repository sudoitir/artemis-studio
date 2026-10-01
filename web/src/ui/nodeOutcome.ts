import type { components } from '../kernel/api/schema.d.ts';

type LifecycleOutcomeView = components['schemas']['LifecycleOutcomeView'];

/**
 * Whether the command actually landed on every node it named — the one question a
 * caller asks before treating the resource as changed.
 *
 * <p>Stated positively on purpose. `partial` is false both when everything worked
 * and when nothing did (a cluster with no live node settles nowhere), so a caller
 * that asks "not partial and nothing failed" concludes a delete succeeded against
 * a cluster it never reached.
 */
export function appliedEverywhere(outcome: LifecycleOutcomeView): boolean {
  return (
    !outcome.dryRun &&
    outcome.nodes.length > 0 &&
    outcome.nodes.every((n) => n.status === 'APPLIED' || n.status === 'ALREADY')
  );
}
