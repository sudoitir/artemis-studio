import { useRef, useState } from 'react';
import { useQueries, useQuery } from '@tanstack/react-query';

import { accessQuery, type AccessSummary } from './api.ts';

/**
 * Client-side reflection of the server's permission model (authorization spec), used only to offer, disable or
 * explain controls a request would be rejected for anyway. The server's own checks are the enforcement; this reads
 * the summary it computes with the same resolver (`/me/access`), so environment grants, teams and shares are
 * counted as the server counts them.
 *
 * <p>`can(permission)` is about the whole installation. `can(permission, clusterId)` is about one cluster, and
 * fetches that cluster's summary the first time it is asked. Until it arrives the control is offered, as it is
 * while grants load: blocking on an answer not yet known would lock out someone the server lets in. For a resource
 * permission the cluster answer counts grants only; what a team or share gives on some queue or address is in the
 * summary's `anywhere`.
 *
 * <p>`canAnywhere` is the page-level right: held on the cluster by a grant, or on some queue or address through a team
 * or a share. A control is hidden only when it is false. `canOn` is the right on one row the server sent, from the
 * row's `allowedActions`: false there is a control shown disabled, with its reason. `createPatterns` are the name
 * patterns a team or share lets the caller create queues and addresses under.
 */
export function useCan() {
  const installation = useQuery(accessQuery());
  const [asked, setAsked] = useState<string[]>([]);
  const knownAsked = useRef(new Set<string>());
  const clusters = useQueries({ queries: asked.map((id) => accessQuery(id)) });
  const byCluster = new Map<string, AccessSummary | undefined>(asked.map((id, i) => [id, clusters[i]?.data]));

  // A cluster is asked about while a component renders, so its summary is requested just after the render.
  function ask(clusterId: string) {
    if (knownAsked.current.has(clusterId)) return;
    knownAsked.current.add(clusterId);
    queueMicrotask(() => setAsked((current) => [...current, clusterId]));
  }

  const summary = installation.data;
  const teamAdmin = summary?.teams.some((t) => t.teamAdmin) ?? false;

  function can(permission: string, clusterId?: string): boolean {
    if (!clusterId) {
      // Holding team:admin in a team is not a grant, yet it is what lets someone manage that team's members.
      return (summary?.permissions.includes(permission) ?? false) || (permission === 'team:admin' && teamAdmin);
    }
    const known = byCluster.get(clusterId);
    if (!known) {
      ask(clusterId);
      return true;
    }
    return known.permissions.includes(permission);
  }

  function canAnywhere(permission: string, clusterId: string): boolean {
    const known = byCluster.get(clusterId);
    if (!known) {
      ask(clusterId);
      return true;
    }
    return known.permissions.includes(permission) || known.anywhere.includes(permission);
  }

  /** How the caller reaches a kind of resource on a cluster: by a grant, only through teams, or not at all. */
  function reach(permission: string, clusterId: string): 'grant' | 'teams' | 'none' | 'unknown' {
    const known = byCluster.get(clusterId);
    if (!known) {
      ask(clusterId);
      return 'unknown';
    }
    if (known.permissions.includes(permission)) return 'grant';
    return known.anywhere.includes(permission) ? 'teams' : 'none';
  }

  function createPatterns(clusterId: string, kind: 'queue' | 'address'): string[] | undefined {
    const known = byCluster.get(clusterId);
    if (!known) ask(clusterId);
    return known?.createPatterns[kind];
  }

  return {
    can,
    canAnywhere,
    reach,
    /** Whether the row the server sent allows the action to the caller. */
    canOn: (permission: string, resource: { allowedActions: string[] }) => resource.allowedActions.includes(permission),
    createPatterns,
    /** The teams the caller belongs to, with their role in each. */
    teams: summary?.teams ?? [],
    loading: installation.isLoading,
  };
}
