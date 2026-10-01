import { useCallback, useEffect, useState } from 'react';
import { Button, Checkbox, Group, Stack, Text } from '@mantine/core';

import { useCluster } from '../clusters/index.ts';
import { useDeleteQueue, useSetQueuePaused, type LifecycleOutcomeView, type QueueView } from './api.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { gateFor } from '../../ui/capabilityGate.ts';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { appliedEverywhere } from '../../ui/nodeOutcome.ts';
import { NodeOutcomeSummary } from '../../ui/NodeOutcomeSummary.tsx';
import { Notice } from '../../ui/Notice.tsx';
import { EditQueueForm } from './EditQueueForm.tsx';

/**
 * Pause, resume, edit and delete for one queue, across every live node.
 *
 * <p>Each action previews before it acts. The delete additionally requires the
 * queue's name typed exactly, because it destroys messages that no dry run can
 * bring back — and the confirmation names the nodes and the message count first,
 * so what is being agreed to is a real number rather than a warning adjective.
 */
export function QueueLifecycleActions({
  clusterId,
  queue,
  onClose,
}: Readonly<{
  clusterId: string;
  queue: QueueView;
  onClose: () => void;
}>) {
  const { can, loading } = useCan();
  const cluster = useCluster(clusterId);
  const write = cluster.data?.capabilities.managementWrite;

  const [deleteOpen, setDeleteOpen] = useState(false);
  const [editOpen, setEditOpen] = useState(false);
  const [pauseOutcome, setPauseOutcome] = useState<LifecycleOutcomeView | null>(null);

  const setPaused = useSetQueuePaused(clusterId, queue.queueName);

  const pending = loading || cluster.isPending;
  const pauseGate = gateFor(can('queue:pause', clusterId), 'Pause and resume queues', write, pending);
  const updateGate = gateFor(can('queue:update', clusterId), "Change a queue's configuration", write, pending);
  const deleteGate = gateFor(can('queue:delete', clusterId), 'Destroy queues and addresses', write, pending);

  // What the queue runs, from the scrape snapshot the listing is built from — and,
  // until that catches up, what this screen just had the broker do. A pause applied
  // on every node but not yet swept showed a button still offering to pause, which
  // reads as an action that did nothing.
  const observedPaused = queue.perNode.some((n) => n.paused);
  const appliedPaused = pauseOutcome?.nodes.every((n) => n.status === 'APPLIED')
    ? setPaused.variables?.paused
    : undefined;
  const paused = appliedPaused ?? observedPaused;
  const awaitingSweep = appliedPaused !== undefined && appliedPaused !== observedPaused;

  return (
    <Stack gap="xs">
      <Group gap="xs">
        <CapabilityGate verdict={pauseGate} what={paused ? 'resuming this queue' : 'pausing this queue'}>
          <Button
            size="xs"
            variant="default"
            disabled={pauseGate.kind === 'blocked'}
            loading={setPaused.isPending}
            onClick={() => setPaused.mutate({ paused: !paused }, { onSuccess: setPauseOutcome })}
          >
            {paused ? 'Resume' : 'Pause'}
          </Button>
        </CapabilityGate>

        <CapabilityGate verdict={updateGate} what="editing this queue">
          <Button
            size="xs"
            variant="default"
            disabled={updateGate.kind === 'blocked'}
            onClick={() => setEditOpen(true)}
          >
            Edit
          </Button>
        </CapabilityGate>

        <CapabilityGate verdict={deleteGate} what="deleting this queue">
          <Button
            size="xs"
            variant="default"
            disabled={deleteGate.kind === 'blocked'}
            onClick={() => setDeleteOpen(true)}
          >
            Delete queue
          </Button>
        </CapabilityGate>
      </Group>

      {/* Stated once, next to the controls it qualifies, rather than as a banner
          the operator has already scrolled past. */}
      {write?.status === 'UNKNOWN' ? (
        <Text size="xs" c="dimmed">
          Studio has not yet seen a management write on this connection, so it cannot promise these will work. The first
          one settles it.
        </Text>
      ) : null}

      <div aria-live="polite">
        {awaitingSweep ? (
          <Text size="xs" c="dimmed">
            {paused ? 'Paused' : 'Resumed'} on every live node. The listing says so once the next sweep reads it back.
          </Text>
        ) : null}
        {setPaused.isError ? <ErrorState error={setPaused.error} variant="inline" /> : null}
        {pauseOutcome ? <NodeOutcomeSummary outcome={pauseOutcome} /> : null}
      </div>

      <EditQueueForm clusterId={clusterId} queue={queue} opened={editOpen} onClose={() => setEditOpen(false)} />

      <DeleteQueueDialog
        clusterId={clusterId}
        queue={queue}
        opened={deleteOpen}
        onClose={() => setDeleteOpen(false)}
        onDeleted={onClose}
      />
    </Stack>
  );
}

/**
 * The destructive flow. Opens on a preview, so the typed confirmation is armed
 * only once the operator has been shown the blast radius: which nodes, how many
 * messages will be destroyed on each, and which diverts go with the queue.
 *
 * <p>Disconnecting consumers is the operator's explicit choice (ADR-0084). Changing
 * it takes the preview again, so what is confirmed is always what was previewed. The result of
 * the delete replaces the confirmation, per node, so a partial delete is read before the dialog is
 * dismissed.
 */
export function DeleteQueueDialog({
  clusterId,
  queue,
  opened,
  onClose,
  onDeleted,
}: Readonly<{
  clusterId: string;
  queue: QueueView;
  opened: boolean;
  onClose: () => void;
  onDeleted: () => void;
}>) {
  const remove = useDeleteQueue(clusterId, queue.queueName);
  const [preview, setPreview] = useState<LifecycleOutcomeView | null>(null);
  const [result, setResult] = useState<LifecycleOutcomeView | null>(null);
  const [previewFailed, setPreviewFailed] = useState<Error | null>(null);
  const [disconnectConsumers, setDisconnectConsumers] = useState(false);
  const { mutate } = remove;

  const takePreview = useCallback(
    (disconnect: boolean) => {
      setPreview(null);
      setResult(null);
      setPreviewFailed(null);
      mutate({ dryRun: true, disconnectConsumers: disconnect }, { onSuccess: setPreview, onError: setPreviewFailed });
    },
    [mutate],
  );

  // The preview runs when the dialog opens, not on a second click: the operator
  // asked to delete, and the estimate is what they need in order to decide.
  useEffect(() => {
    if (opened) takePreview(false);
  }, [opened, takePreview]);

  const close = () => {
    setPreview(null);
    setResult(null);
    setPreviewFailed(null);
    setDisconnectConsumers(false);
    remove.reset();
    onClose();
  };

  // The queue view behind this dialog is dismissed only when the queue is really gone, which is a
  // positive check on every node: a run that failed everywhere, or reached no live node at all, is
  // not partial either.
  const dismiss = () => {
    const gone = result !== null && appliedEverywhere(result);
    close();
    if (gone) onDeleted();
  };

  const consumers = queue.totalConsumerCount;

  const overCap = preview?.overCap ?? false;

  return (
    <ConfirmDialog
      opened={opened}
      onClose={dismiss}
      title={result ? `Result of deleting ${queue.queueName}` : `Delete ${queue.queueName}`}
      tone="danger"
      typedName={queue.queueName}
      confirmLabel={overCap ? 'Delete anyway, over the cap' : 'Delete this queue'}
      // Also locked while the preview is being taken, so the typed name cannot arm a delete that
      // has not been shown its blast radius.
      pending={remove.isPending}
      onConfirm={() =>
        remove.mutate({ dryRun: false, override: overCap, disconnectConsumers }, { onSuccess: setResult })
      }
      result={result ? <NodeOutcomeSummary outcome={result} destructive /> : undefined}
      consequence={
        result ? null : (
          <Stack gap="md">
            <Text size="sm">
              This destroys the queue on every live node of the cluster, along with every message it holds. Nothing here
              can be undone, and a queue recreated afterwards is a new, empty one.
            </Text>
            <Text size="sm">
              A divert that forwards into this queue&apos;s address is removed with it when the delete leaves nothing
              bound there — otherwise the divert would bring the queue back, or break its producers. Each node below
              names the diverts it removes and the ones it keeps.
            </Text>

            <Checkbox
              label="Disconnect this queue's consumers"
              description={`${consumers.toLocaleString()} ${
                consumers === 1 ? 'consumer was' : 'consumers were'
              } attached at the last scrape. Without this, a node where the queue has consumers refuses the delete. A client that reconnects can create the queue again if auto-create is on.`}
              checked={disconnectConsumers}
              // Locked while any call is in flight: a new preview on the same mutation would drop
              // the real delete's result, and the operator would never see what it did.
              disabled={remove.isPending}
              onChange={(e) => {
                const next = e.currentTarget.checked;
                setDisconnectConsumers(next);
                takePreview(next);
              }}
            />

            <div aria-live="polite">
              {remove.isPending && !preview ? (
                <Text size="sm" c="dimmed">
                  Counting what would be destroyed…
                </Text>
              ) : null}

              {/* An unavailable estimate is stated, never omitted — an absent number
                  reads as zero, which is exactly the wrong thing to infer here. */}
              {previewFailed ? (
                <ErrorState
                  variant="inline"
                  error={previewFailed}
                  next="The estimate could not be taken. The delete can still proceed, but Studio cannot tell you how many messages it would destroy."
                />
              ) : null}

              {preview ? <NodeOutcomeSummary outcome={preview} destructive /> : null}
            </div>

            {overCap && preview ? (
              <Notice tone="warning" title="Over the safety cap">
                This would destroy {preview.totalAffected.toLocaleString()} messages, over the cap of{' '}
                {preview.cap.toLocaleString()}. Confirming will override the cap for this operation, and the override is
                recorded in the audit log.
              </Notice>
            ) : null}

            {remove.isError && !previewFailed ? <ErrorState error={remove.error} variant="inline" /> : null}
          </Stack>
        )
      }
    />
  );
}
