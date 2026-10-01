import { Group, Progress, Stack, Text } from '@mantine/core';

import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { ApplyProgress } from './applyProgress.ts';

/** The bar takes the semantic tone of a phase that is not going well; the phase is also said in words. */
const PROGRESS_COLOR = { danger: 'var(--as-danger)', warning: 'var(--as-warning)' } as const;

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
export function ApplyTimeline({ progress }: Readonly<{ progress: ApplyProgress[] }>) {
  if (progress.length === 0) {
    return (
      <Text size="sm" role="status">
        Applying — waiting for the first node to report…
      </Text>
    );
  }
  return (
    <Stack gap="sm" role="status">
      {progress.map((p) => {
        const phase = PHASE[p.phase] ?? { text: p.phase };
        return (
          <Stack key={p.nodeId} gap="xs">
            <Group gap="sm" align="center">
              <Text size="sm" fw={600} component="span">
                {p.nodeName}
                {p.canary ? ' (canary)' : ''}
              </Text>
              <StatusBadge tone={phase.tone ?? 'neutral'}>{phase.text}</StatusBadge>
              {p.total > 0 ? (
                <Text size="sm" c="dimmed" component="span">
                  step {Math.min(p.done, p.total)} of {p.total}
                </Text>
              ) : null}
            </Group>
            <Progress
              value={p.total === 0 ? 100 : (p.done / p.total) * 100}
              size="xs"
              color={phase.tone ? PROGRESS_COLOR[phase.tone] : undefined}
              aria-hidden
            />
          </Stack>
        );
      })}
    </Stack>
  );
}
