import type { components } from '../../kernel/api/schema.d.ts';

type Endpoint = components['schemas']['NodeEndpointView'];
type Topology = components['schemas']['TopologyView'];

export const endpointsOf = (topology: Topology | undefined): Endpoint[] =>
  (topology?.nodes ?? []).flatMap((n) => n.endpoints);

/** A node that can take part in a transfer now: live, a primary, and managed by Studio. */
export const serving = (e: Endpoint) => e.active && e.haRole !== 'BACKUP' && e.manageable;

/** Why a node cannot be a transfer's target, in words; null when it can. */
export function unavailableReason(e: Endpoint): string | null {
  if (e.haRole === 'BACKUP') return 'a backup, which takes messages only through its live peer';
  if (!e.manageable) return 'not managed by Studio: it has no management URL';
  if (!e.active) return 'not live now';
  return null;
}

/** The node Select's options: every node stays listed, and one that cannot be chosen says why. */
export function nodeOptions(endpoints: Endpoint[], exclude?: { id: string; reason: string }) {
  return endpoints.map((e) => {
    const reason = e.id === exclude?.id ? exclude.reason : unavailableReason(e);
    // A node without Core stays choosable: the preview refuses it and shows the acceptor to add.
    const note = !reason && !e.coreUrl ? ' (no Core connection: the preview shows what to add)' : '';
    return { value: e.id, label: reason ? `${e.name}: ${reason}` : `${e.name}${note}`, disabled: reason !== null };
  });
}
