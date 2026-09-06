import { useState } from 'react';
import { Alert, Button, Group, Modal, Stack, Text } from '@mantine/core';

import {
  useCluster,
  useDeleteQueue,
  useSetQueuePaused,
  type LifecycleOutcomeView,
  type QueueView,
} from '../api/client.ts';
import { useCan } from '../auth/useCan.ts';
import { CapabilityGate } from '../shared/CapabilityGate.tsx';
import { gateFor } from '../shared/capabilityGate.ts';
import { ConfirmByTyping } from '../shared/ConfirmByTyping.tsx';
import { NodeOutcomeSummary } from '../shared/NodeOutcomeSummary.tsx';
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
}: {
  clusterId: string;
  queue: QueueView;
  onClose: () => void;
}) {
  const { can, loading } = useCan();
  const cluster = useCluster(clusterId);
  const write = cluster.data?.capabilities.managementWrite;

  const [deleteOpen, setDeleteOpen] = useState(false);
  const [editOpen, setEditOpen] = useState(false);
  const [pauseOutcome, setPauseOutcome] = useState<LifecycleOutcomeView | null>(null);

  const setPaused = useSetQueuePaused(clusterId, queue.queueName);

  const pending = loading || cluster.isPending;
  const pauseGate = gateFor(can('queue:pause', clusterId), 'Pause and resume queues', write, pending);
  const updateGate = gateFor(
    can('queue:update', clusterId),
    "Change a queue's configuration",
    write,
    pending,
  );
  const deleteGate = gateFor(can('queue:delete', clusterId), 'Destroy queues and addresses', write, pending);

  const paused = queue.perNode.some((n) => n.paused);

  return (
    <Stack gap="xs">
      <Group gap="xs">
        <CapabilityGate verdict={pauseGate}>
          <Button
            size="xs"
            variant="light"
            disabled={pauseGate.kind === 'blocked'}
            loading={setPaused.isPending}
            onClick={() =>
              setPaused.mutate(
                { paused: !paused },
                { onSuccess: setPauseOutcome },
              )
            }
          >
            {paused ? 'Resume' : 'Pause'}
          </Button>
        </CapabilityGate>

        <CapabilityGate verdict={updateGate}>
          <Button
            size="xs"
            variant="light"
            disabled={updateGate.kind === 'blocked'}
            onClick={() => setEditOpen(true)}
          >
            Edit
          </Button>
        </CapabilityGate>

        <CapabilityGate verdict={deleteGate}>
          <Button
            size="xs"
            variant="light"
            color="red"
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
          Studio has not yet seen a management write on this connection, so it cannot promise these
          will work. The first one settles it.
        </Text>
      ) : null}

      <div aria-live="polite">
        {setPaused.isError ? (
          <Alert color="red" variant="light" title={setPaused.error.title} role="alert">
            {setPaused.error.message}
          </Alert>
        ) : null}
        {pauseOutcome ? <NodeOutcomeSummary outcome={pauseOutcome} /> : null}
      </div>

      <EditQueueForm
        clusterId={clusterId}
        queue={queue}
        opened={editOpen}
        onClose={() => setEditOpen(false)}
      />

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
 * only once the operator has been shown the blast radius: which nodes, and how
 * many messages will be destroyed on each.
 */
function DeleteQueueDialog({
  clusterId,
  queue,
  opened,
  onClose,
  onDeleted,
}: {
  clusterId: string;
  queue: QueueView;
  opened: boolean;
  onClose: () => void;
  onDeleted: () => void;
}) {
  const remove = useDeleteQueue(clusterId, queue.queueName);
  const [preview, setPreview] = useState<LifecycleOutcomeView | null>(null);
  const [result, setResult] = useState<LifecycleOutcomeView | null>(null);
  const [previewFailed, setPreviewFailed] = useState<string | null>(null);

  // The preview runs when the dialog opens, not on a second click: the operator
  // asked to delete, and the estimate is what they need in order to decide.
  const onOpen = () => {
    setPreview(null);
    setResult(null);
    setPreviewFailed(null);
    remove.mutate(
      { dryRun: true },
      {
        onSuccess: setPreview,
        onError: (e) => setPreviewFailed(e.message),
      },
    );
  };

  const close = () => {
    setPreview(null);
    setResult(null);
    setPreviewFailed(null);
    remove.reset();
    onClose();
  };

  const overCap = preview?.overCap ?? false;

  return (
    <Modal
      opened={opened}
      onClose={close}
      onEnterTransitionEnd={onOpen}
      title={`Delete ${queue.queueName}`}
      size="lg"
    >
      <Stack gap="md">
        <Text size="sm">
          This destroys the queue on every live node of the cluster, along with every message it
          holds. Nothing here can be undone, and a queue recreated afterwards is a new, empty one.
        </Text>

        <div aria-live="polite">
          {remove.isPending && !preview ? (
            <Text size="sm" c="dimmed">
              Counting what would be destroyed…
            </Text>
          ) : null}

          {/* An unavailable estimate is stated, never omitted — an absent number
              reads as zero, which is exactly the wrong thing to infer here. */}
          {previewFailed ? (
            <Alert color="yellow" variant="light" title="The estimate could not be taken" role="alert">
              {previewFailed} The delete can still proceed, but Studio cannot tell you how many
              messages it would destroy.
            </Alert>
          ) : null}

          {preview ? <NodeOutcomeSummary outcome={preview} destructive /> : null}
          {result ? <NodeOutcomeSummary outcome={result} destructive /> : null}
        </div>

        {overCap && preview ? (
          <Alert color="yellow" variant="light" title="Over the safety cap">
            This would destroy {preview.totalAffected.toLocaleString()} messages, over the cap of{' '}
            {preview.cap.toLocaleString()}. Confirming will override the cap for this operation, and
            the override is recorded in the audit log.
          </Alert>
        ) : null}

        {remove.isError && !previewFailed ? (
          <Alert color="red" variant="light" title={remove.error.title} role="alert">
            {remove.error.message}
          </Alert>
        ) : null}

        {result ? (
          <Group justify="flex-end">
            <Button
              size="xs"
              onClick={() => {
                close();
                if (!result.partial) onDeleted();
              }}
            >
              Close
            </Button>
          </Group>
        ) : (
          <ConfirmByTyping
            token={queue.queueName}
            confirmLabel={overCap ? 'Delete anyway, over the cap' : 'Delete this queue'}
            loading={remove.isPending && preview !== null}
            disabled={remove.isPending}
            onConfirm={() =>
              remove.mutate(
                { override: overCap },
                { onSuccess: setResult },
              )
            }
          />
        )}
      </Stack>
    </Modal>
  );
}
