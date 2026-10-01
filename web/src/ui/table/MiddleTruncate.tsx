import classes from './DataTable.module.css';

/** How much of the end of an identifier always stays visible: its distinguishing suffix. */
const TAIL_CHARS = 12;

/**
 * An identifier shortened in the middle, in CSS alone: the start shrinks and ends in an ellipsis
 * while the last characters stay, so names that share a prefix (`DLQ.orders.a1b2…`, `DLQ.orders.c3d4…`)
 * stay distinguishable. The full value is the cell's `title` and its reveal panel.
 */
export function MiddleTruncate({ text }: Readonly<{ text: string }>) {
  const chars = Array.from(text);
  if (chars.length <= TAIL_CHARS) return <>{text}</>;
  return (
    <span className={classes.middle}>
      <span data-clip className={classes.middleStart}>
        {chars.slice(0, -TAIL_CHARS).join('')}
      </span>
      <span className={classes.middleTail}>{chars.slice(-TAIL_CHARS).join('')}</span>
    </span>
  );
}
