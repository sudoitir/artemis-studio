import { Button, Text } from '@mantine/core';

import { useVerifyOnBroker, type SqlRowView } from '../api/client.ts';

/**
 * "Is this indexed message still on its queue?" — asked of the broker, which is
 * the only thing that can answer it.
 *
 * <p>Three outcomes, never two. An unknown verdict is rendered as unknown rather
 * than folded into "gone": the index already says what was seen, and a false
 * "gone" during an incident sends an operator looking for a message that is
 * sitting on the queue in front of them.
 */
export function VerifyOnBroker({ clusterId, row }: { clusterId: string; row: SqlRowView }) {
  const verify = useVerifyOnBroker(clusterId);
  const verdict = verify.data;

  if (verify.isError) {
    return (
      <Text size="xs" c="var(--as-danger)">
        Could not ask: {verify.error.message}
      </Text>
    );
  }

  if (verdict) {
    // Colour is redundant emphasis here; the word carries the meaning.
    const tone =
      verdict.presence === 'GONE'
        ? 'var(--as-warning)'
        : verdict.presence === 'UNKNOWN'
          ? 'var(--as-text-dimmed)'
          : undefined;
    const words =
      verdict.presence === 'PRESENT'
        ? 'still there'
        : verdict.presence === 'GONE'
          ? 'gone'
          : 'unknown';
    return (
      <Text size="xs" c={tone} title={verdict.detail ?? undefined}>
        {words}
      </Text>
    );
  }

  return (
    <Button
      size="compact-xs"
      variant="default"
      loading={verify.isPending}
      onClick={(e) => {
        // The row itself opens the message; verifying is a second action on the
        // same row and must not do both.
        e.stopPropagation();
        verify.mutate(row);
      }}
    >
      Verify
    </Button>
  );
}
