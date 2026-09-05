import type { ReactNode } from 'react';

import { useMe, type GrantView } from '../api/client.ts';

/**
 * Client-side reflection of the server's permission model (authorization
 * spec) — used only to hide/disable controls a request would be rejected for
 * anyway. The server's `@PreAuthorize`/`ClusterAccessGuard` checks are the
 * real enforcement; this never substitutes for them.
 */
export function useCan() {
  const me = useMe();

  function can(permission: string, clusterId?: string): boolean {
    const grants = me.data?.grants ?? [];
    return grants.some((g) => scopeMatches(g, clusterId) && grantHas(g, permission));
  }

  return { can, grants: me.data?.grants ?? [], loading: me.isLoading };
}

function scopeMatches(grant: GrantView, clusterId?: string): boolean {
  if (grant.scopeType === 'GLOBAL') return true;
  if (!clusterId) return false;
  // Environment scoping is resolved server-side only — the client does not
  // carry a cluster->environment map. A CLUSTER-scoped grant on this exact
  // cluster still short-circuits most UI checks correctly; anything the
  // client can't resolve stays server-enforced regardless.
  return grant.scopeType === 'CLUSTER' && grant.scopeId === clusterId;
}

function grantHas(grant: GrantView, permission: string): boolean {
  if (grant.permissions.includes('*') || grant.permissions.includes(permission)) return true;
  const resource = permission.split(':')[0];
  return grant.permissions.includes(`${resource}:*`);
}

export interface CanProps {
  permission: string;
  clusterId?: string;
  children: ReactNode;
  fallback?: ReactNode;
}

/** Renders `children` only if the current user holds `permission` (optionally scoped to `clusterId`). */
export function Can({ permission, clusterId, children, fallback = null }: CanProps) {
  const { can } = useCan();
  return can(permission, clusterId) ? <>{children}</> : <>{fallback}</>;
}
