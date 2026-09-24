import { Code, Stack, Text } from '@mantine/core';

/**
 * Why a control is unavailable, in full: the reason, and the `broker.xml` that enables it where one
 * exists (non-negotiable #5). The one rendering of it, shared by the {@link CapabilityGate} popover
 * and the explanation a blocked menu item opens, so both say the same thing the same way.
 */
export function CapabilityReason({
  reason,
  snippet,
  size = 'xs',
}: {
  reason: string;
  snippet?: string | null;
  /** `xs` in a popover; `sm` where the explanation is the whole dialog. */
  size?: 'xs' | 'sm';
}) {
  return (
    <Stack gap="xs">
      <Text size={size}>{reason}</Text>
      {snippet ? (
        <>
          <Text size="xs" fw={600}>
            Add this to <Code>broker.xml</Code>:
          </Text>
          <Code block style={{ fontSize: 11, whiteSpace: 'pre-wrap' }}>
            {snippet}
          </Code>
        </>
      ) : null}
    </Stack>
  );
}
