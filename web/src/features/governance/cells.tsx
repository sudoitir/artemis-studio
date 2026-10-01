import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { RuleView } from './api.ts';
import classes from './Governance.module.css';
import { targetLabel } from './words.ts';

/** A rule's selector, marked when it is built in or came from a dismissed finding, with what it matches. */
export function SelectorCell({ rule: r }: Readonly<{ rule: RuleView }>) {
  return (
    <span className={classes.lines}>
      <span className={classes.inline}>
        <code>{r.selector}</code>
        {r.builtin ? <StatusBadge>built-in</StatusBadge> : null}
        {r.exception ? <StatusBadge>dismissed finding</StatusBadge> : null}
      </span>
      <span className={classes.note}>{targetLabel(r.target)}</span>
    </span>
  );
}
