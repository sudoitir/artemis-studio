import { Stack, Text } from '@mantine/core';

import { useRrFlows } from '../api/client.ts';
import { FlowsTable } from './FlowsTable.tsx';
import { STUCK_STATES } from './rrState.ts';

/**
 * The panel an operator opens at 3am: awaiting-reply flows past half their
 * deadline, plus every terminal non-completed state, oldest first. One query
 * over every flow, filtered client-side — simpler than one hook per state and
 * the flow volume this screen deals with is small by construction (Phase 5
 * traces a handful of declared addresses, not a whole broker's traffic).
 */
export function StuckPanel({
  clusterId,
  onSelect,
}: {
  clusterId: string;
  onSelect: (flowId: string) => void;
}) {
  const query = useRrFlows(clusterId, { size: 500 });

  if (query.isPending) {
    return (
      <Text size="sm" c="dimmed">
        Loading…
      </Text>
    );
  }

  const stuck = (query.data?.data ?? [])
    .filter((f) => (STUCK_STATES as readonly string[]).includes(f.state))
    .sort((a, b) => new Date(a.requestedAt).getTime() - new Date(b.requestedAt).getTime());

  return (
    <Stack gap="sm">
      <Text size="xs" c="dimmed">
        {stuck.length} flow{stuck.length === 1 ? '' : 's'} timed out, orphaned, or dropped — oldest
        first.
      </Text>
      <FlowsTable flows={stuck} onSelect={onSelect} />
    </Stack>
  );
}
