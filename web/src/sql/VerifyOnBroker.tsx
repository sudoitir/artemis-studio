import { Button, Text, Tooltip } from "@mantine/core";

import { useVerifyOnBroker, type SqlRowView } from "../api/client.ts";

/**
 * "Is this indexed message still on its queue?" — asked of the broker, which is
 * the only thing that can answer it.
 *
 * <p>Three outcomes, never two. An unknown verdict is rendered as unknown rather
 * than folded into "gone": the index already says what was seen, and a false
 * "gone" during an incident sends an operator looking for a message that is
 * sitting on the queue in front of them.
 *
 * <p>A captured row can only be verified through the id its source message had,
 * which the broker copies onto the diverted copy as `_AMQ_ORIG_MESSAGE_ID`. Where
 * that header is absent the control stays visible and disabled with the reason
 * given, never hidden: a missing button would teach the operator that the product
 * cannot verify captured rows at all.
 */
export function VerifyOnBroker({
  clusterId,
  row,
}: {
  clusterId: string;
  row: SqlRowView;
}) {
  const verify = useVerifyOnBroker(clusterId);
  const verdict = verify.data;
  const unverifiable = row.origin === "CAPTURED" && row.sourceMessageId == null;

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
      verdict.presence === "GONE"
        ? "var(--as-warning)"
        : verdict.presence === "UNKNOWN"
          ? "var(--as-text-dimmed)"
          : undefined;
    const words =
      verdict.presence === "PRESENT"
        ? "still there"
        : verdict.presence === "GONE"
          ? "gone"
          : "unknown";
    return (
      <Text size="xs" c={tone} title={verdict.detail ?? undefined}>
        {words}
      </Text>
    );
  }

  if (unverifiable) {
    // The explanation hangs off a focusable wrapper, because a disabled button
    // takes no focus and a hover-only reason is unreachable by keyboard.
    return (
      <Tooltip
        label={
          "This broker did not record which message this copy came from, so there is nothing " +
          "to look up on the queue. Artemis copies _AMQ_ORIG_MESSAGE_ID onto a diverted message."
        }
        multiline
        w={300}
      >
        <span tabIndex={0}>
          <Button size="compact-xs" variant="default" disabled>
            Verify
          </Button>
        </span>
      </Tooltip>
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
