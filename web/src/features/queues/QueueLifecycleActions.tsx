import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, Checkbox, Group, Modal, Stack, Text } from '@mantine/core';

import { useCluster } from '../clusters/index.ts';
import { useDeleteQueue, useSetQueuePaused, type LifecycleOutcomeView, type QueueView } from './api.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { gateFor } from '../../ui/capabilityGate.ts';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { appliedEverywhere } from '../../ui/nodeOutcome.ts';
import { NodeOutcomeSummary } from '../../ui/NodeOutcomeSummary.tsx';
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
  const deleteTrigger = useRef<HTMLButtonElement>(null);
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
        <CapabilityGate verdict={pauseGate}>
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

        <CapabilityGate verdict={updateGate}>
          <Button
            size="xs"
            variant="default"
            disabled={updateGate.kind === 'blocked'}
            onClick={() => setEditOpen(true)}
          >
            Edit
          </Button>
        </CapabilityGate>

        <CapabilityGate verdict={deleteGate}>
          <Button
            ref={deleteTrigger}
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
        restoreFocus={() => deleteTrigger.current?.focus()}
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
  restoreFocus,
}: Readonly<{
  clusterId: string;
  queue: QueueView;
  opened: boolean;
  onClose: () => void;
  onDeleted: () => void;
  /** Where focus goes once the result is dismissed, when no host does it. */
  restoreFocus?: () => void;
}>) {
  const remove = useDeleteQueue(clusterId, queue.queueName);
  const [preview, setPreview] = useState<LifecycleOutcomeView | null>(null);
  const [result, setResult] = useState<LifecycleOutcomeView | null>(null);
  const [previewFailed, setPreviewFailed] = useState<string | null>(null);
  const [disconnectConsumers, setDisconnectConsumers] = useState(false);
  const { mutate } = remove;

  const takePreview = useCallback(
    (disconnect: boolean) => {
      setPreview(null);
      setResult(null);
      setPreviewFailed(null);
      mutate(
        { dryRun: true, disconnectConsumers: disconnect },
        { onSuccess: setPreview, onError: (e) => setPreviewFailed(e.message) },
      );
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

  const consumers = queue.totalConsumerCount;

  const overCap = preview?.overCap ?? false;

  return (
    <>
      <ConfirmDialog
        opened={opened && result === null}
        onClose={close}
        title={`Delete ${queue.queueName}`}
        tone="danger"
        typedName={queue.queueName}
        confirmLabel={overCap ? 'Delete anyway, over the cap' : 'Delete this queue'}
        // Also locked while the preview is being taken, so the typed name cannot arm a delete that
        // has not been shown its blast radius.
        pending={remove.isPending}
        onConfirm={() =>
          remove.mutate({ dryRun: false, override: overCap, disconnectConsumers }, { onSuccess: setResult })
        }
        consequence={
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
                <Alert variant="default" title="The estimate could not be taken" role="alert">
                  {previewFailed} The delete can still proceed, but Studio cannot tell you how many messages it would
                  destroy.
                </Alert>
              ) : null}

              {preview ? <NodeOutcomeSummary outcome={preview} destructive /> : null}
            </div>

            {overCap && preview ? (
              <Alert variant="default" title="Over the safety cap">
                This would destroy {preview.totalAffected.toLocaleString()} messages, over the cap of{' '}
                {preview.cap.toLocaleString()}. Confirming will override the cap for this operation, and the override is
                recorded in the audit log.
              </Alert>
            ) : null}

            {remove.isError && !previewFailed ? <ErrorState error={remove.error} variant="inline" /> : null}
          </Stack>
        }
      />

      <Modal
        opened={opened && result !== null}
        onClose={close}
        // The confirmation that opened this one has already handed focus back; without a host to
        // restore it, the opener is focused once this is gone.
        returnFocus={restoreFocus === undefined}
        onExitTransitionEnd={restoreFocus}
        title={`Result of deleting ${queue.queueName}`}
        size="lg"
      >
        <Stack gap="md">
          <div aria-live="polite">{result ? <NodeOutcomeSummary outcome={result} destructive /> : null}</div>
          <Group justify="flex-end">
            <Button
              size="xs"
              onClick={() => {
                const done = result !== null && appliedEverywhere(result);
                close();
                // The queue view behind this dialog is dismissed only when the queue is
                // really gone, which is a positive check on every node: a run that failed
                // everywhere, or reached no live node at all, is not partial either.
                if (done) onDeleted();
              }}
            >
              Close
            </Button>
          </Group>
        </Stack>
      </Modal>
    </>
  );
}
