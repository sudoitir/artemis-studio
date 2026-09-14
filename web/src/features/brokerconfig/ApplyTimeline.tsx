import { Progress, Stack, Text } from '@mantine/core';

import type { ApplyProgress } from './applyProgress.ts';
import classes from './Configuration.module.css';

const PHASE: Record<ApplyProgress['phase'], { text: string; tone?: 'warning' | 'danger' }> = {
  APPLYING: { text: 'applying' },
  VERIFYING: { text: 'reading back to verify' },
  DONE: { text: 'applied and verified' },
  HALTED: { text: 'halted here', tone: 'danger' },
  UNREACHABLE: { text: 'could not be reached', tone: 'warning' },
};

/**
 * Where the apply has got to, while it is still running.
 *
 * An apply across a pair is a sequence the operator has reason to watch: the
 * canary is written and read back before anything else is touched, and if it
 * fails nothing else is attempted. Without this the whole sequence is one
 * spinner, and a halt on the canary looks identical to a slow success.
 *
 * Advisory. The POST's response replaces this with the real per-node result, so
 * nothing here is ever the last word on what happened.
 */
export function ApplyTimeline({ progress }: { progress: ApplyProgress[] }) {
  if (progress.length === 0) {
    return (
      <Text size="sm" aria-live="polite">
        Applying — waiting for the first node to report…
      </Text>
    );
  }
  return (
    <Stack gap={6} aria-live="polite">
      {progress.map((p) => {
        const phase = PHASE[p.phase] ?? { text: p.phase };
        return (
          <div key={p.nodeId}>
            <Text size="xs">
              <Text component="span" size="xs" fw={600}>
                {p.nodeName}
                {p.canary ? ' (canary)' : ''}
              </Text>{' '}
              <Text component="span" size="xs" className={classes.state} data-tone={phase.tone}>
                {phase.text}
              </Text>
              {p.total > 0 ? (
                <Text component="span" size="xs" c="dimmed">
                  {' '}
                  — step {Math.min(p.done, p.total)} of {p.total}
                </Text>
              ) : null}
            </Text>
            <Progress
              value={p.total === 0 ? 100 : (p.done / p.total) * 100}
              size="xs"
              mt={2}
              color={phase.tone === 'danger' ? 'red' : phase.tone === 'warning' ? 'yellow' : undefined}
              aria-hidden
            />
          </div>
        );
      })}
    </Stack>
  );
}
