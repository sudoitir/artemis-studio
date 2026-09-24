import { useState } from 'react';
import { Alert, Anchor, Button, Group, Modal, Skeleton, Stack, Text, Title } from '@mantine/core';
import { Link, useParams } from '@tanstack/react-router';

import { useClusters } from '../clusters/index.ts';
import { AddressPicker } from '../queues/index.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import { absoluteLabel } from '../../kernel/time/time.ts';
import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { gateFor } from '../../ui/capabilityGate.ts';
import { ConfirmByTyping } from '../../ui/ConfirmByTyping.tsx';
import { VirtualTable, type GridColumn } from '../../ui/VirtualTable.tsx';
import { useOrphans, useReturnOrphan, useTransferRuns, type OrphanView, type TransferRunView } from './api.ts';
import { MODE, plural, stateWords, toneColor } from './words.ts';

/**
 * Staging left on a broker by a run Studio no longer has: the one way messages could strand.
 * It is never cleaned up behind the operator's back — each one is listed with its depth and a
 * return action that names the queue the messages go back to.
 */
function Orphans({ clusterId }: { clusterId: string }) {
  const query = useOrphans(clusterId);
  const returnOrphan = useReturnOrphan(clusterId);
  const { can, loading } = useCan();
  const [chosen, setChosen] = useState<OrphanView | null>(null);
  const [queue, setQueue] = useState('');
  const gate = gateFor(can('message:move', clusterId), 'Move or retry messages', undefined, loading);

  // Not being able to look is not the same as nothing being there, and is said rather than hidden.
  if (query.isError) {
    return (
      <Alert color="yellow" variant="light" title="Could not check for stranded staging queues">
        {query.error.message} If a transfer was interrupted, its messages may still be held on a broker. Open this
        screen again once the cluster answers.
      </Alert>
    );
  }
  // Nothing stranded is the normal case, and says nothing.
  if (!query.data || query.data.length === 0) return null;

  return (
    <Stack gap="xs">
      <Title order={4}>Staging queues with no run</Title>
      <Text size="sm">
        These hold messages a transfer took off a queue before Studio lost the run. Nothing is lost: return each
        one to the queue the messages came from, and Studio removes the staging queue afterwards.
      </Text>
      {query.data.map((orphan) => (
        <Group key={`${orphan.nodeId}:${orphan.stagingQueue}`} gap="sm">
          <Text size="sm" style={{ fontVariantNumeric: 'tabular-nums' }}>
            {orphan.stagingQueue} on {orphan.nodeName} — {orphan.depth == null ? 'depth unknown' : plural(orphan.depth, 'message')}
          </Text>
          <CapabilityGate verdict={gate} what="returning these messages">
            <Button
              size="xs"
              variant="light"
              disabled={gate.kind === 'blocked'}
              onClick={() => {
                returnOrphan.reset();
                setQueue('');
                setChosen(orphan);
              }}
            >
              Return to…
            </Button>
          </CapabilityGate>
        </Group>
      ))}

      <div aria-live="polite">
        {returnOrphan.data ? (
          <Text size="sm">
            {plural(returnOrphan.data.returned, 'message')} returned from {returnOrphan.data.stagingQueue}.{' '}
            {returnOrphan.data.removed
              ? 'The staging queue is gone.'
              : `${plural(returnOrphan.data.remaining, 'message')} could not be taken and the staging queue is still there.`}
          </Text>
        ) : null}
      </div>

      <Modal
        opened={chosen !== null}
        onClose={() => setChosen(null)}
        title={chosen ? `Return the messages held in ${chosen.stagingQueue}` : ''}
      >
        {chosen ? (
          <Stack gap="sm">
            <Text size="sm">
              Every message in {chosen.stagingQueue} on {chosen.nodeName} moves to the queue you name, on that same
              node, and the staging queue is then removed.
            </Text>
            <AddressPicker
              clusterId={clusterId}
              label="Return them to"
              description="The queue the transfer took them from."
              value={queue}
              onChange={setQueue}
              unknownHint="No queue by that name on this cluster. Check it before returning messages to it."
            />
            {returnOrphan.isError ? (
              <Alert color="red" variant="light" title={returnOrphan.error.title} role="alert">
                {returnOrphan.error.message} Nothing was returned.
              </Alert>
            ) : null}
            <ConfirmByTyping
              token={chosen.stagingQueue}
              label={`Type the staging queue's name, "${chosen.stagingQueue}", to confirm`}
              confirmLabel="Return the messages"
              color="red"
              loading={returnOrphan.isPending}
              disabled={queue.trim() === '' || returnOrphan.isPending}
              onConfirm={() =>
                returnOrphan.mutate(
                  { nodeId: chosen.nodeId, stagingQueue: chosen.stagingQueue, targetQueue: queue.trim() },
                  { onSuccess: () => setChosen(null) },
                )
              }
            />
          </Stack>
        ) : null}
      </Modal>
    </Stack>
  );
}

/** Transfers where this cluster is the source or the target, newest first, and any stranded staging. */
export function TransfersView() {
  useDisplayZone();
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const query = useTransferRuns(clusterId);
  const clusters = useClusters();
  const clusterName = (id: string) => clusters.data?.find((c) => c.id === id)?.name ?? 'another cluster';

  const end = (run: TransferRunView, which: 'source' | 'target') =>
    `${run[which].queue} on ${run[which].nodeName}${run[which].clusterId === clusterId ? '' : ` (${clusterName(run[which].clusterId)})`}`;

  const columns: GridColumn<TransferRunView>[] = [
    {
      id: 'when',
      header: 'When',
      accessor: (r) => absoluteLabel(r.startedAt ?? r.createdAt),
      // A real link, so each run is reachable from the keyboard.
      cell: (r) => (
        <Anchor component={Link} to={`/clusters/${clusterId}/transfers/${r.id}`} size="sm">
          {absoluteLabel(r.startedAt ?? r.createdAt)}
        </Anchor>
      ),
      width: 210,
    },
    { id: 'mode', header: 'Mode', accessor: (r) => MODE[r.mode].verb, width: 80 },
    { id: 'from', header: 'From', accessor: (r) => end(r, 'source') },
    { id: 'to', header: 'To', accessor: (r) => end(r, 'target') },
    { id: 'delivered', header: 'Delivered', accessor: (r) => r.delivered, numeric: true, width: 110 },
    {
      id: 'state',
      header: 'Outcome',
      accessor: (r) => stateWords(r.state).text,
      cell: (r) => {
        const state = stateWords(r.state);
        return (
          <Text size="sm" c={toneColor(state.tone)}>
            {state.text}
          </Text>
        );
      },
    },
    { id: 'user', header: 'Run by', accessor: (r) => r.username, width: 160 },
  ];

  return (
    <Stack gap="sm">
      <Title order={3}>Message transfers</Title>
      <Orphans clusterId={clusterId} />
      {query.isError ? (
        <Alert color="red" variant="light" title={query.error.title}>
          {query.error.message}
        </Alert>
      ) : !query.data ? (
        <Skeleton height={160} />
      ) : (
        <VirtualTable
          label="Transfers"
          columns={columns}
          data={query.data}
          rowKey={(r) => r.id}
          emptyLabel={
            <Stack gap={4} align="flex-start">
              <Text fw={600}>No transfers yet</Text>
              <Text size="sm">
                A transfer moves or copies messages from a queue to a queue on another node or another cluster,
                after a preview that checks the target can accept them. Select messages on the Messages screen to
                start one.
              </Text>
              <Anchor component={Link} to={`/clusters/${clusterId}/queues`} size="sm">
                Go to Queues
              </Anchor>
            </Stack>
          }
        />
      )}
    </Stack>
  );
}
