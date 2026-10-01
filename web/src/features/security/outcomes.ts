import { notify, type ActionVerb } from '../../ui/notify.ts';

/**
 * Announces one mutation's outcomes: a pending line now, then the success or the failure that
 * replaces it. A failure states its cause (the server's message) and `next`, what the operator does
 * about it. Spread the result into `mutate`'s options; `onSuccess` runs after the announcement.
 */
export function withNotice(action: ActionVerb, subject: string, next: string, onSuccess?: () => void) {
  const pendingId = notify.pending({ action, subject });
  return {
    onSuccess: () => {
      notify.succeeded({ action, subject, pendingId });
      onSuccess?.();
    },
    onError: (error: Error) => notify.failed({ action, subject, pendingId, cause: error.message, next }),
  };
}
