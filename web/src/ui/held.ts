/** An operation held for approval, as read from the error a held request throws. */
export interface Held {
  id: string;
  summary: string;
}

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null;

/**
 * The held operation an error carries, read by its shape (the kernel's `OperationHeldError`), so the
 * shared parts recognise it without importing the kernel. `undefined` for any other error.
 */
export function heldOf(error: unknown): Held | undefined {
  if (!isRecord(error) || error.name !== 'OperationHeldError' || !isRecord(error.heldOperation)) return undefined;
  const { id, summary } = error.heldOperation;
  if (typeof id !== 'string' || !id) return undefined;
  return { id, summary: typeof summary === 'string' && summary ? summary : 'The operation' };
}

/** The in-app page of one approval request. */
export const approvalPath = (id: string) => `/approvals/${encodeURIComponent(id)}`;
