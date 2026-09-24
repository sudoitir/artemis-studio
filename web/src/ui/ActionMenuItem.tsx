import { useId, type MouseEvent, type ReactNode } from 'react';
import { Menu } from '@mantine/core';

import type { GateVerdict } from './capabilityGate.ts';
import classes from './ActionMenu.module.css';

type Blocked = Extract<GateVerdict, { kind: 'blocked' }>;

/** The first sentence of a reason: what fits under a menu label. The full text is one activation away. */
function firstSentence(text: string): string {
  const end = text.search(/\.\s/);
  return end > 0 ? text.slice(0, end + 1) : text;
}

/**
 * One item of a row's action menu (ADR-0105).
 *
 * <p>An item the operator may not use is never removed and never `disabled`: Mantine skips disabled
 * items in keyboard navigation, which would leave a keyboard user no way to learn why. It is
 * `aria-disabled`, states the first sentence of its reason, and activating it hands the full reason
 * (with its `broker.xml`) to `onExplain`.
 *
 * <p>A navigation item takes `href`, so it can be opened in a new tab; a plain activation still goes
 * through `onSelect`, which navigates inside the app.
 */
export function ActionMenuItem({
  label,
  icon,
  verdict = { kind: 'allowed', uncertain: false },
  tone,
  href,
  onSelect,
  onExplain,
}: {
  label: string;
  icon?: ReactNode;
  verdict?: GateVerdict;
  /** `danger` for an action that destroys something; the word still carries it. */
  tone?: 'danger';
  href?: string;
  onSelect: () => void;
  onExplain?: (verdict: Blocked) => void;
}) {
  const hintId = useId();
  const blocked = verdict.kind === 'blocked';
  const hint = blocked
    ? firstSentence(verdict.reason)
    : verdict.uncertain
      ? 'Not yet proven on this connection; the first attempt settles it.'
      : null;

  const activate = (event: MouseEvent<HTMLElement>) => {
    if (blocked) {
      event.preventDefault();
      onExplain?.(verdict);
      return;
    }
    // A modified click on a link opens it where the operator asked; everything else stays in the app.
    if (href && (event.metaKey || event.ctrlKey || event.shiftKey || event.button === 1)) return;
    if (href) event.preventDefault();
    onSelect();
  };

  const body = (
    <>
      <span className={classes.label}>{label}</span>
      {hint ? (
        <span id={hintId} className={classes.hint}>
          {hint}
        </span>
      ) : null}
    </>
  );

  const className = `${classes.item} ${tone === 'danger' && !blocked ? classes.danger : ''}`;
  const shared = {
    className,
    leftSection: icon,
    'aria-disabled': blocked || undefined,
    'aria-describedby': hint ? hintId : undefined,
    onClick: activate,
  };

  return href && !blocked ? (
    <Menu.Item component="a" href={href} {...shared}>
      {body}
    </Menu.Item>
  ) : (
    <Menu.Item {...shared}>{body}</Menu.Item>
  );
}
