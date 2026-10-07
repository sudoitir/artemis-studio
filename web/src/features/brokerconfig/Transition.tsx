import { MiddleTruncate } from '../../ui/table/MiddleTruncate.tsx';
import classes from './Configuration.module.css';

/**
 * A value that moved, `before → after`, on one line. Each side is shortened in the middle when the pair
 * does not fit, so the arrow and the side that matters stay in view whatever the names are.
 */
export function Transition({ before, after }: Readonly<{ before: string; after: string }>) {
  return (
    <span className={classes.transition}>
      <span className={`${classes.before} ${classes.side}`}>
        <MiddleTruncate text={before} tooltip />
      </span>
      <span aria-hidden="true"> → </span>
      <span className={classes.side}>
        <MiddleTruncate text={after} tooltip />
      </span>
    </span>
  );
}
