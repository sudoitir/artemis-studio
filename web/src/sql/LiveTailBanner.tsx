import { Alert, Badge, Button, Group, Stack, Text } from '@mantine/core';

import type { SqlTailStatusView } from '../api/client.ts';
import classes from './LiveTailBanner.module.css';

function time(iso?: string): string {
  if (!iso) return 'not yet';
  const at = new Date(iso);
  return Number.isNaN(at.getTime()) ? 'not yet' : at.toLocaleTimeString();
}

/**
 * What the tail can and cannot tell you.
 *
 * <p>The unobserved figure is only a figure when the query has no predicate: then
 * every enqueued message would have matched, and enqueued minus shown is exactly
 * what passed through between two reads. With a predicate the same subtraction
 * also contains messages that simply did not match, and the two cannot be
 * separated — so it is described rather than reported as a number, because a
 * number an operator cannot trust is worse than a sentence they can.
 */
function gapWords(tail: SqlTailStatusView | null): string {
  if (!tail || (tail.enqueued ?? 0) === 0) {
    return 'No message has been enqueued on these queues since the tail started.';
  }
  const enqueued = (tail.enqueued ?? 0).toLocaleString();
  const shown = (tail.shown ?? 0).toLocaleString();
  if (tail.everyMessageMatches) {
    const missed = Math.max(0, (tail.enqueued ?? 0) - (tail.shown ?? 0));
    return missed === 0
      ? `${enqueued} enqueued, ${shown} shown — nothing has passed through unobserved yet.`
      : `${enqueued} enqueued, ${shown} shown: about ${missed.toLocaleString()} passed through between reads and were never seen.`;
  }
  return `${enqueued} enqueued on these queues while tailing, ${shown} shown here. The difference holds both messages that did not match this query and messages consumed between reads, and the two cannot be told apart.`;
}

/**
 * The live tail's pinned header.
 *
 * <p>The sampling statement is not dismissable and is not a tooltip. A tail that
 * looks like a capture is read as one, and an operator concluding "the message
 * never arrived" from a sampled feed is the exact failure this screen exists to
 * prevent — so the limitation stays on screen for as long as the tail runs.
 */
export function LiveTailBanner({
  tail,
  shown,
  onStop,
}: {
  tail: SqlTailStatusView | null;
  shown: number;
  onStop: () => void;
}) {
  return (
    <div className={classes.pinned}>
      <Alert color="yellow" variant="light" title="Live tail — this is a sample, not a capture">
        <Stack gap={6}>
          <Text size="sm">
            Studio re-reads these queues every few seconds. A message that arrives and is consumed
            between two reads is never seen, so an empty tail is not evidence that nothing was sent.
          </Text>
          <Text size="sm">{gapWords(tail)}</Text>
          <Group gap="xs" justify="space-between" wrap="wrap">
            <Group gap="xs">
              <Badge size="sm" variant="light" color="gray">
                {shown.toLocaleString()} row{shown === 1 ? '' : 's'} so far
              </Badge>
              <Text size="xs" c="dimmed">
                {(tail?.polls ?? 0).toLocaleString()} read{(tail?.polls ?? 0) === 1 ? '' : 's'} · last
                at {time(tail?.lastPollAt)}
              </Text>
            </Group>
            <Button size="compact-xs" variant="default" onClick={onStop}>
              Stop tailing
            </Button>
          </Group>
        </Stack>
      </Alert>
    </div>
  );
}
