import type { ReactNode } from 'react';
import { Button, Popover } from '@mantine/core';

import type { GateVerdict } from './capabilityGate.ts';
import classes from './CapabilityGate.module.css';
import { CapabilityReason } from './CapabilityReason.tsx';

/**
 * Wraps a control that may be unavailable, keeping it visible and explaining
 * itself in place (non-negotiable #5). A silently missing button teaches the
 * operator that the product cannot do something, when the truth is that this
 * connection is not configured for it.
 *
 * <p>The explanation is a popover on a separate, focusable "Why?" button beside the
 * disabled control, not a `title` or a hover tooltip, because a disabled control takes
 * no focus and a keyboard user would otherwise have no way to reach the reason at all.
 * The two are siblings, never nested: a button inside a button is invalid markup.
 *
 * <p>`what` names the control being explained. A screen with several gated
 * controls otherwise announces the same "Why this is unavailable" for each of
 * them, which tells a screen-reader user nothing about which one it belongs to.
 */
export function CapabilityGate({
  verdict,
  what,
  children,
}: Readonly<{
  verdict: GateVerdict;
  /** "applying address setting orders.#"; defaults to "this". */
  what?: string;
  children: ReactNode;
}>) {
  if (verdict.kind === 'allowed') {
    return <>{children}</>;
  }
  return (
    <span className={classes.root}>
      {children}
      <Popover width="21.25rem" position="bottom-end" withArrow shadow="md">
        <Popover.Target>
          <Button variant="subtle" size="compact-xs" aria-label={`Why ${what ?? 'this'} is unavailable`}>
            Why?
          </Button>
        </Popover.Target>
        <Popover.Dropdown>
          <CapabilityReason reason={verdict.reason} snippet={verdict.snippet} />
        </Popover.Dropdown>
      </Popover>
    </span>
  );
}
