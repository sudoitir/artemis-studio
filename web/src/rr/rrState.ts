/** The six rr_flow states, grouped by which `--as-rr-*` token colors them (non-negotiable #6). */
export function stateColorVar(state: string): string {
  if (state === 'AWAITING_REPLY') return 'var(--as-rr-in-flight)';
  if (state === 'COMPLETED') return 'var(--as-rr-resolved)';
  return 'var(--as-rr-failed)'; // TIMED_OUT, ORPHANED, RESPONDER_DROPPED, ORPHANED_REPLY
}

export function stateLabel(state: string): string {
  switch (state) {
    case 'AWAITING_REPLY':
      return 'awaiting reply';
    case 'COMPLETED':
      return 'completed';
    case 'TIMED_OUT':
      return 'timed out';
    case 'ORPHANED':
      return 'orphaned';
    case 'RESPONDER_DROPPED':
      return 'responder dropped';
    case 'ORPHANED_REPLY':
      return 'orphaned reply';
    default:
      return state.toLowerCase();
  }
}

export const STUCK_STATES = ['TIMED_OUT', 'ORPHANED', 'RESPONDER_DROPPED', 'ORPHANED_REPLY'] as const;
