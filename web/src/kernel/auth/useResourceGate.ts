import { gateFor, type GateVerdict } from '../../ui/capabilityGate.ts';
import type { components } from '../api/schema.d.ts';
import { useCan } from './useCan.ts';

type CapabilityView = components['schemas']['CapabilityView'];

/** What a row the server sent says about the caller: the actions allowed on it, and the team that owns it. */
export interface ResourceAccess {
  allowedActions: string[];
  ownerTeam?: { name: string } | null;
}

/**
 * The state of one control on one queue or address.
 *
 * <p>`hidden` only when the caller cannot take the action anywhere on the cluster: it is not theirs to be offered.
 * Otherwise the control is shown, and `verdict` is blocked, with the permission and whom to ask, when this resource
 * is one the action is not allowed on (the row's `allowedActions`). Without a row the cluster-level answer decides.
 */
export function useResourceGate(options: {
  clusterId: string;
  noun: 'queue' | 'address';
  permission: string;
  label: string;
  resource?: ResourceAccess;
  capability?: CapabilityView;
  /** Something other than grants is still loading. */
  pending?: boolean;
}): { hidden: boolean; verdict: GateVerdict } {
  const { clusterId, noun, permission, label, resource, capability, pending = false } = options;
  const { can, canAnywhere, canOn, loading } = useCan();
  const permitted = resource ? canOn(permission, resource) : can(permission, clusterId);
  return {
    hidden: !loading && !canAnywhere(permission, clusterId),
    verdict: gateFor(
      permitted,
      label,
      capability,
      loading || pending,
      'cluster',
      resource ? { permission, noun, owner: resource.ownerTeam?.name } : undefined,
    ),
  };
}
