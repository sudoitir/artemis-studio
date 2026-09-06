import type { CapabilityView } from '../api/client.ts';

export type GateVerdict =
  | { kind: 'allowed'; uncertain: boolean }
  | { kind: 'blocked'; reason: string; snippet?: string | null };

/**
 * Whether a lifecycle control may act, and why not when it may not.
 *
 * <p>Two independent gates, in the order the operator can do something about:
 *
 * <ul>
 *   <li><b>Permission</b> — the caller's own grants. The server is the
 *       enforcement point; this only explains, and never hides.
 *   <li><b>Capability</b> — whether the broker connection can write at all. Only
 *       a known refusal blocks. Since ADR-0049 D5 an unproven capability is
 *       reported as unknown, and blocking on the absence of evidence would stop
 *       an operator using a broker that works perfectly well.
 * </ul>
 */
export function gateFor(
  permitted: boolean,
  permissionLabel: string,
  capability: CapabilityView | undefined,
  /** True while the caller's grants are still being fetched. */
  loading = false,
): GateVerdict {
  // Nothing is known yet. Saying "you do not have permission" here would be a
  // claim about the operator that has not been checked, and it would flash on
  // every render before settling. The server is the enforcement point either
  // way, so the honest interim answer is to offer the control.
  if (loading) {
    return { kind: 'allowed', uncertain: false };
  }
  if (!permitted) {
    return {
      kind: 'blocked',
      reason: `You do not have the "${permissionLabel}" permission on this cluster. An administrator can grant it in Settings → Roles.`,
    };
  }
  if (capability?.status === 'UNAVAILABLE') {
    return { kind: 'blocked', reason: capability.reason, snippet: capability.brokerXmlSnippet };
  }
  return { kind: 'allowed', uncertain: capability?.status === 'UNKNOWN' };
}
