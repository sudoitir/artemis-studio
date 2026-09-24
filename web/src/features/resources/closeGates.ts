import { useCluster } from '../clusters/index.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import { gateFor, type GateVerdict } from '../../ui/capabilityGate.ts';
import type { ConnectionCloseKind } from './api.ts';

const PERMISSION_LABEL = "Close client connections, sessions and an address's consumers";

type NodeKind = Exclude<ConnectionCloseKind, 'address-consumers'>;

const NOUN: Record<NodeKind, string> = {
  connection: 'connection',
  session: 'session',
  consumer: "consumer's connection",
};

/**
 * Whether a close may be attempted on this target, and why not. A row the broker gave no
 * identifier for has nothing to aim at; the control still shows, with the reason — a missing
 * button would read as "Studio cannot close connections" rather than "this row cannot be
 * addressed".
 */
export function useCloseGate(clusterId: string, kind: NodeKind, targetId: string): GateVerdict {
  const { can, loading } = useCan();
  const cluster = useCluster(clusterId);
  const permitted = gateFor(
    can('connection:close', clusterId),
    PERMISSION_LABEL,
    cluster.data?.capabilities.managementWrite,
    loading || cluster.isPending,
  );
  return targetId
    ? permitted
    : {
        kind: 'blocked',
        reason: `This broker reported no identifier for this ${NOUN[kind]}, so there is nothing to aim a close at.`,
      };
}

/** Whether closing an address's consumers may be attempted, and why not. */
export function useCloseAddressGate(clusterId: string): GateVerdict {
  const { can, loading } = useCan();
  const cluster = useCluster(clusterId);
  return gateFor(
    can('connection:close', clusterId),
    PERMISSION_LABEL,
    cluster.data?.capabilities.managementWrite,
    loading || cluster.isPending,
  );
}
