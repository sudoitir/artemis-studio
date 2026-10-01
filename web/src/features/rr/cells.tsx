import { Stack, Text } from '@mantine/core';

import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { ExpectationDiagnosticsView, ExpectationView } from './api.ts';
import { stateLabel, stateTone } from './rrState.ts';
import { resolutionWords, statusText } from './words.ts';
import classes from './rr.module.css';

/** A flow's state in words; only a failed one takes colour. */
export function FlowStateBadge({ state }: Readonly<{ state: string }>) {
  return <StatusBadge tone={stateTone(state)}>{stateLabel(state)}</StatusBadge>;
}

/**
 * One expectation's declared reply addresses, and what they resolve to right now.
 *
 * The declaration and the resolution are both shown because they answer different
 * questions: the patterns say what the operator meant, the resolved set says what is
 * actually being browsed this minute. A pattern matching nothing yet is normal — the
 * responder has not started — so it reads as a state, not an error.
 */
export function ReplyAddressesCell({ expectation: e }: Readonly<{ expectation: ExpectationView }>) {
  if (e.replyAddresses.length === 0) {
    return (
      <Text size="sm" c="dimmed">
        temporary queues
      </Text>
    );
  }

  const resolved = e.resolvedReplyAddresses;
  const patterns = e.replyAddresses.filter((a) => a.includes('*'));

  return (
    <Stack gap="xs">
      <Text size="sm">{e.replyAddresses.join(', ')}</Text>
      {patterns.length > 0 || e.replyAddressesCapped ? (
        <Text
          size="xs"
          c={e.replyAddressesCapped || resolved.length === 0 ? undefined : 'dimmed'}
          className={e.replyAddressesCapped || resolved.length === 0 ? classes.warn : undefined}
        >
          {resolutionWords(e.replyAddressesCapped, resolved.length)}
        </Text>
      ) : null}
    </Stack>
  );
}

/**
 * One traced address's last tick, in a sentence an operator can act on.
 *
 * The interesting states are all "Studio looked and found nothing" versus "Studio
 * never looked", which the flows list alone cannot distinguish.
 */
export function ExpectationStatus({
  status,
  now,
}: Readonly<{ status: ExpectationDiagnosticsView | undefined; now: number }>) {
  if (!status || !status.enabled) {
    return (
      <Text size="xs" c="dimmed">
        {statusText(status, now)}
      </Text>
    );
  }
  const trouble = status.skipped.length > 0 || status.lastError !== null;
  return (
    <Stack gap="xs">
      <Text size="xs" c={trouble ? undefined : 'dimmed'} className={trouble ? classes.warn : undefined}>
        {statusText(status, now)}
      </Text>
      {status.skipped.length > 0 ? (
        <Text size="xs" className={classes.warn}>
          {status.skipped[0]}
        </Text>
      ) : null}
      {status.rateExceedsInterval ? (
        <Text size="xs" className={classes.warn}>
          asks for more samples than the sampler interval delivers
        </Text>
      ) : null}
    </Stack>
  );
}
