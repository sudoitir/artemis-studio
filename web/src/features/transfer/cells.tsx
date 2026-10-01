import { Anchor } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import { absoluteLabel } from '../../kernel/time/time.ts';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { TransferRunView } from './api.ts';
import { stateWords } from './words.ts';

/** The run's start time, as a real link, so each run is reachable from the keyboard. */
export function RunLink({ run, clusterId }: Readonly<{ run: TransferRunView; clusterId: string }>) {
  return (
    <Anchor component={Link} to={`/clusters/${clusterId}/transfers/${run.id}`} size="sm">
      {absoluteLabel(run.startedAt ?? run.createdAt)}
    </Anchor>
  );
}

/** The run's outcome in words; colour only emphasises a run that went wrong or needs the operator. */
export function RunOutcome({ run }: Readonly<{ run: TransferRunView }>) {
  const { text, tone } = stateWords(run.state);
  return <StatusBadge tone={tone}>{text}</StatusBadge>;
}
