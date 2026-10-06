import { matchesPattern } from './pattern.ts';
import { useCan } from './useCan.ts';

/**
 * Where the caller may create a queue or an address on a cluster: anywhere when a grant reaches the cluster,
 * otherwise only under the name patterns a team or share gives. `restriction` says so for a form to show, and
 * `problem` checks a name as it is typed. Unknown while the summary loads, which restricts nothing; the server
 * refuses a name outside the patterns either way.
 */
export function useCreateRule(clusterId: string, kind: 'queue' | 'address') {
  const { can, createPatterns } = useCan();
  const anywhere = can(`${kind}:create`, clusterId);
  const nouns = kind === 'queue' ? 'queues' : 'addresses';
  const patterns = anywhere ? [] : (createPatterns(clusterId, kind) ?? []);
  return {
    anywhere,
    patterns,
    /** The sentence that tells where the caller may create; none when nothing limits them. */
    restriction: anywhere
      ? null
      : patterns.length > 0
        ? `You may create ${nouns} under: ${patterns.join(', ')}.`
        : `You may not create ${nouns} on this cluster.`,
    /** What is wrong with `name`, or null when it is empty or allowed. */
    problem: (name: string): string | null => {
      const trimmed = name.trim();
      if (anywhere || trimmed === '' || patterns.some((p) => matchesPattern(p, trimmed))) return null;
      return patterns.length > 0
        ? `${trimmed} is outside the patterns you may create ${nouns} under (${patterns.join(', ')}).`
        : `You may not create ${nouns} on this cluster.`;
    },
  };
}
