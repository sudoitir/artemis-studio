import type { ReactNode } from 'react';
import { Code, Popover, Stack, Text, UnstyledButton } from '@mantine/core';

import type { GateVerdict } from './capabilityGate.ts';

/**
 * Wraps a control that may be unavailable, keeping it visible and explaining
 * itself in place (non-negotiable #5). A silently missing button teaches the
 * operator that the product cannot do something, when the truth is that this
 * connection is not configured for it.
 *
 * <p>The explanation is a popover on a real focusable button, not a `title` or a
 * hover tooltip, because a disabled control takes no focus and a keyboard user
 * would otherwise have no way to reach the reason at all.
 *
 * <p>`what` names the control being explained. A screen with several gated
 * controls otherwise announces the same "Why this is unavailable" for each of
 * them, which tells a screen-reader user nothing about which one it belongs to.
 */
export function CapabilityGate({
  verdict,
  what,
  children,
}: {
  verdict: GateVerdict;
  /** "applying address setting orders.#"; defaults to "this". */
  what?: string;
  children: ReactNode;
}) {
  if (verdict.kind === 'allowed') {
    return <>{children}</>;
  }
  return (
    <Popover width={340} position="bottom-end" withArrow shadow="md">
      <Popover.Target>
        {/* The wrapper takes the focus the disabled control cannot. */}
        <UnstyledButton
          aria-label={`Why ${what ?? 'this'} is unavailable`}
          style={{ display: 'inline-flex', cursor: 'help' }}
        >
          <span style={{ pointerEvents: 'none' }}>{children}</span>
        </UnstyledButton>
      </Popover.Target>
      <Popover.Dropdown>
        <Stack gap="xs">
          <Text size="xs">{verdict.reason}</Text>
          {verdict.snippet ? (
            <>
              <Text size="xs" fw={600}>
                Add this to <Code>broker.xml</Code>:
              </Text>
              <Code block style={{ fontSize: 11, whiteSpace: 'pre-wrap' }}>
                {verdict.snippet}
              </Code>
            </>
          ) : null}
        </Stack>
      </Popover.Dropdown>
    </Popover>
  );
}
