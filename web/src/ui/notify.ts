import { notifications } from '@mantine/notifications';

/**
 * The three forms of one action's verb, so a mutation's toast names it as its button does: the
 * button "Delete" is followed by "Deleting…", then "Deleted". Written out rather than derived,
 * because English inflection is irregular.
 */
export type ActionVerb = Readonly<{
  /** As on the control, such as "Delete". */
  verb: string;
  /** The outcome, such as "Deleted". */
  past: string;
  /** While it runs, such as "Deleting". */
  progressive: string;
}>;

type Notice = Readonly<{
  action: ActionVerb;
  /** What the action acts on, such as `queue "orders"`. */
  subject: string;
}>;

/** Settles a toast that {@link notify.pending} returned: it is replaced by the outcome. */
type Settles = Readonly<{ pendingId?: string }>;

// The edge of each toast takes its tone from the semantic layer; the words carry the meaning.
const tone = (token: string) => ({ '--notification-color': `var(${token})` });

// Mantine renders every toast as role="alert" (assertive). Only a failure should interrupt a screen
// reader, so everything else is role="status" (polite).
const POLITE = 'status';
const ASSERTIVE = 'alert';

/** Long enough to read and see the edge settle; a failure never closes by itself. */
const SUCCEEDED_MS = 6000;

function show(settles: Settles, toast: Parameters<typeof notifications.show>[0]) {
  if (settles.pendingId) notifications.hide(settles.pendingId);
  return notifications.show(toast);
}

/**
 * One helper for the four outcomes of a mutation (pending, succeeded, failed, partial), over
 * Mantine's notifications, which must be mounted once at the root.
 *
 * <ul>
 *   <li>Every line names the outcome with the action's own verb ({@link ActionVerb}).
 *   <li>Pending and succeeded are announced politely. Failed and partial are announced
 *       assertively and stay until dismissed, because each carries a next step to act on.
 *   <li>A failure states its cause and the next action; both are required.
 *   <li>A toast that stays until dismissed can be dismissed by the caller with {@link notify.dismiss},
 *       such as a failure the operator has since fixed by retrying.
 * </ul>
 */
export const notify = {
  /** The action is running. Returns the toast's id; pass it as `pendingId` to the outcome. */
  pending({ action, subject }: Notice): string {
    return notifications.show({
      message: `${action.progressive} ${subject}…`,
      loading: true,
      autoClose: false,
      withCloseButton: false,
      role: POLITE,
      style: tone('--as-accent'),
    });
  },

  /** The action finished everywhere. */
  succeeded({ action, subject, pendingId }: Notice & Settles) {
    return show(
      { pendingId },
      {
        message: `${action.past} ${subject}`,
        autoClose: SUCCEEDED_MS,
        role: POLITE,
        style: tone('--as-ok'),
      },
    );
  },

  /** The action did not happen. `cause` says why; `next` says what to do. */
  failed({ action, subject, pendingId, cause, next }: Notice & Settles & Readonly<{ cause: string; next: string }>) {
    return show(
      { pendingId },
      {
        title: `Could not ${action.verb.toLowerCase()} ${subject}`,
        message: `${cause} ${next}`,
        autoClose: false,
        role: ASSERTIVE,
        style: tone('--as-danger'),
      },
    );
  },

  /**
   * The action reached some of what it was asked to. `reached` says how far, such as "2 of 3
   * nodes"; `next` says what is left to do.
   */
  partial({
    action,
    subject,
    pendingId,
    reached,
    next,
  }: Notice & Settles & Readonly<{ reached: string; next: string }>) {
    return show(
      { pendingId },
      {
        title: `${action.past} ${subject} on ${reached}`,
        message: next,
        autoClose: false,
        role: ASSERTIVE,
        style: tone('--as-warning'),
      },
    );
  },

  /** Closes a toast by the id one of the outcomes returned. */
  dismiss(id: string) {
    notifications.hide(id);
  },
};
