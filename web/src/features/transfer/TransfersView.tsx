import { useMemo, useState } from 'react';
import { Alert, Anchor, Button, Group, Modal, Stack, Text, Title } from '@mantine/core';
import { Link, useParams } from '@tanstack/react-router';

import { useClusters } from '../clusters/index.ts';
import { AddressPicker } from '../queues/index.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { gateFor } from '../../ui/capabilityGate.ts';
import { ConfirmByTyping } from '../../ui/ConfirmByTyping.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { Page } from '../../ui/Page.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { useOrphans, useReturnOrphan, useTransferRuns, type OrphanView, type TransferRunView } from './api.ts';
import { transferColumns } from './columns.ts';
import { plural } from './words.ts';

/**
 * Staging left on a broker by a run Studio no longer has: the one way messages could strand.
 * It is never cleaned up behind the operator's back — each one is listed with its depth and a
 * return action that names the queue the messages go back to.
 */
function Orphans({ clusterId }: Readonly<{ clusterId: string }>) {
  const query = useOrphans(clusterId);
  const returnOrphan = useReturnOrphan(clusterId);
  const { can, loading } = useCan();
  const [chosen, setChosen] = useState<OrphanView | null>(null);
  const [queue, setQueue] = useState('');
  const gate = gateFor(can('message:move', clusterId), 'Move or retry messages', undefined, loading);

  // Not being able to look is not the same as nothing being there, and is said rather than hidden.
  if (query.isError) {
    return (
      <Alert variant="light" title="Could not check for stranded staging queues">
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
        These hold messages a transfer took off a queue before Studio lost the run. Nothing is lost: return each one to
        the queue the messages came from, and Studio removes the staging queue afterwards.
      </Text>
      {query.data.map((orphan) => (
        <Group key={`${orphan.nodeId}:${orphan.stagingQueue}`} gap="sm">
          <Text size="sm" style={{ fontVariantNumeric: 'tabular-nums' }}>
            {orphan.stagingQueue} on {orphan.nodeName} —{' '}
            {orphan.depth == null ? 'depth unknown' : plural(orphan.depth, 'message')}
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
              <Alert variant="light" title={returnOrphan.error.title} role="alert">
                {returnOrphan.error.message} Nothing was returned.
              </Alert>
            ) : null}
            <ConfirmByTyping
              token={chosen.stagingQueue}
              label={`Type the staging queue's name, "${chosen.stagingQueue}", to confirm`}
              confirmLabel="Return the messages"
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

const rowKey = (r: TransferRunView) => r.id;

/** Transfers where this cluster is the source or the target, newest first, and any stranded staging. */
export function TransfersView() {
  const zone = useDisplayZone();
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const query = useTransferRuns(clusterId);
  const clusters = useClusters();
  const clusterList = clusters.data;
  const columns = useMemo(
    () =>
      transferColumns({
        clusterId,
        clusterName: (id) => clusterList?.find((c) => c.id === id)?.name ?? 'another cluster',
        zone,
      }),
    [clusterId, clusterList, zone],
  );

  return (
    <Page fill>
      <Stack gap="sm">
        <Title order={3}>Message transfers</Title>
        <Orphans clusterId={clusterId} />
      </Stack>
      <DataTable
        label="Transfers"
        storageKey="transfers"
        height="fill"
        columns={columns}
        data={query.data ?? []}
        rowKey={rowKey}
        loading={query.isPending}
        error={query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : undefined}
        empty={
          <EmptyState
            kind="empty"
            title="No transfers yet"
            description="A transfer moves or copies messages from a queue to a queue on another node or another cluster, after a preview that checks the target can accept them. None has been run from or to this cluster yet. Select messages on the Messages screen to start one."
            action={
              <Anchor component={Link} to={`/clusters/${clusterId}/queues`} size="sm">
                Go to Queues
              </Anchor>
            }
          />
        }
      />
    </Page>
  );
}
