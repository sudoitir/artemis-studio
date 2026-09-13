/**
 * The result grid's column vocabulary, apart from the component that renders it so
 * the picker and the grid cannot disagree about what a column is called.
 */

/** Every column the result grid can show, in its default order. */
export const ALL_COLUMN_IDS = [
  "source",
  "node",
  "queue",
  "messageId",
  "timestamp",
  "priority",
  "size",
  "body",
  "verify",
] as const;

export const COLUMN_LABELS: Record<string, string> = {
  source: "Source",
  node: "Node",
  queue: "Queue",
  messageId: "Message ID",
  timestamp: "Enqueued",
  priority: "Prio",
  size: "Size",
  body: "Body",
  verify: "On broker",
};
