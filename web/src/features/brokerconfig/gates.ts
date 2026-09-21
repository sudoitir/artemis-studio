import { useCluster } from '../clusters/index.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import { gateFor, type GateVerdict } from '../../ui/capabilityGate.ts';
import type { ConfigDeclarationView } from './api.ts';
import { APPLY_PERMISSION_LABEL } from './ReviewApplyDrawer.tsx';
import { CONFIG_MANAGED_REASON } from './words.ts';

const WRITE_PERMISSION_LABEL = 'Edit declared configuration';

/**
 * Whether the declaration may be edited and applied, and why not. The Configuration screen and
 * the routing builder both offer both, so the reasons are decided once.
 */
export function useDeclarationGates(
  clusterId: string,
  /** Absent while the declaration loads; apply is then blocked on it, and nothing offers it yet. */
  declaration: ConfigDeclarationView | undefined,
): {
  /** Grants still loading count as allowed: the server is the enforcement point. */
  canWrite: boolean;
  writeGate: GateVerdict;
  applyGate: GateVerdict;
} {
  const cluster = useCluster(clusterId);
  const { can, loading } = useCan();

  const writeGate = gateFor(can('config:write', clusterId), WRITE_PERMISSION_LABEL, undefined, loading);
  const applyPermission = gateFor(
    can('config:apply', clusterId),
    APPLY_PERMISSION_LABEL,
    cluster.data?.capabilities.managementWrite,
    loading || cluster.isPending,
  );
  const applyGate: GateVerdict =
    declaration?.applyMode === 'CONFIG_MANAGED'
      ? { kind: 'blocked', reason: CONFIG_MANAGED_REASON }
      : !declaration?.declared
        ? {
            kind: 'blocked',
            reason: 'Nothing is declared yet. Adopt from the cluster, import broker.xml or add an entry first.',
          }
        : applyPermission;

  return { canWrite: loading || can('config:write', clusterId), writeGate, applyGate };
}
