/** How a flow state is emphasised: the four that mean the flow went wrong, against the rest. The word carries it. */
export function stateTone(state: string): 'neutral' | 'danger' {
  return state === 'AWAITING_REPLY' || state === 'COMPLETED' ? 'neutral' : 'danger'; // TIMED_OUT, ORPHANED, RESPONDER_DROPPED, ORPHANED_REPLY
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
