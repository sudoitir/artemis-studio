import { useCan } from '../../kernel/auth/useCan.ts';
import type { GateVerdict } from '../../ui/capabilityGate.ts';

/**
 * What the caller may change about teams. A user administrator changes everything; a team admin only the
 * members of their own team. Grants still loading count as allowed, so the controls are offered until the answer
 * is known (the server enforces either way).
 */
export function useTeamAccess(): {
  userAdmin: boolean;
  /** True until the caller's access is known; until then everything is offered. */
  loading: boolean;
  verdict: (what: string) => GateVerdict;
} {
  const { can, loading } = useCan();
  const userAdmin = loading || can('user:admin');
  return {
    userAdmin,
    loading,
    verdict: (what) =>
      userAdmin
        ? { kind: 'allowed', uncertain: false }
        : { kind: 'blocked', reason: `${what} needs the user:admin permission. Ask a user administrator.` },
  };
}
