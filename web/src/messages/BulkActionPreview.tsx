import { useState } from 'react';
import { Alert, Button, Group, Modal, Stack, Text, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';

import {
  useMessageAction,
  type DryRunView,
  type MessageActionKind,
} from '../api/client.ts';
import { ConfirmByTyping } from '../shared/ConfirmByTyping.tsx';

const LABEL: Record<MessageActionKind, string> = {
  move: 'Move',
  retry: 'Retry',
  delete: 'Delete',
  expire: 'Expire',
};

/**
 * By-selector destructive action with a mandatory preview (ADR-0022). "Preview"
 * runs `dryRun=true` and shows the broker's point-in-time estimate and the cap.
 * Over the cap, a typed confirmation of the queue name gates "Run anyway", which
 * resends with `override=true`.
 */
export function BulkActionPreview({
  clusterId,
  queueName,
  action,
  node,
  opened,
  onClose,
  onDone,
}: {
  clusterId: string;
  queueName: string;
  action: MessageActionKind;
  node?: string;
  opened: boolean;
  onClose: () => void;
  onDone: () => void;
}) {
  const run = useMessageAction(clusterId, queueName);
  const [filter, setFilter] = useState('');
  const [target, setTarget] = useState('');
  const [preview, setPreview] = useState<DryRunView | null>(null);

  const body = () => ({
    filter,
    targetQueue: action === 'move' ? target : undefined,
  });

  const doPreview = () => {
    setPreview(null);
    run.mutate(
      { action, body: body(), node, dryRun: true },
      {
        onSuccess: (r) => setPreview('cap' in r ? (r as DryRunView) : null),
        onError: (e) => notifications.show({ color: 'red', message: e.message }),
      },
    );
  };

  const execute = (override: boolean) =>
    run.mutate(
      { action, body: body(), node, override },
      {
        onSuccess: (r) => {
          notifications.show({ message: `${LABEL[action]}d ${'affectedCount' in r ? r.affectedCount : ''} messages` });
          reset();
          onDone();
        },
        onError: (e) => notifications.show({ color: 'red', message: e.message }),
      },
    );

  const reset = () => {
    setFilter('');
    setTarget('');
    setPreview(null);
  };

  return (
    <Modal
      opened={opened}
      onClose={() => {
        reset();
        onClose();
      }}
      title={`${LABEL[action]} by selector`}
      size="lg"
    >
      <Stack gap="sm">
        <TextInput
          label="Selector"
          placeholder="region = 'eu' AND priority > 4"
          value={filter}
          onChange={(e) => setFilter(e.currentTarget.value)}
          size="xs"
        />
        {action === 'move' ? (
          <TextInput
            label="Target queue"
            value={target}
            onChange={(e) => setTarget(e.currentTarget.value)}
            size="xs"
          />
        ) : null}

        <Group>
          <Button
            size="xs"
            variant="default"
            loading={run.isPending}
            disabled={!filter || (action === 'move' && !target)}
            onClick={doPreview}
          >
            Preview
          </Button>
        </Group>

        {preview ? (
          <Alert
            color={preview.overCap ? 'red' : 'blue'}
            variant="light"
            title={`≈ ${preview.affectedCount} messages (estimate)`}
          >
            <Stack gap="xs">
              <Text size="sm">
                Point-in-time count from the broker. Safety cap: {preview.cap}.
              </Text>
              {preview.overCap ? (
                <ConfirmByTyping
                  token={queueName}
                  confirmLabel={`${LABEL[action]} ${preview.affectedCount} messages anyway`}
                  loading={run.isPending}
                  onConfirm={() => execute(true)}
                />
              ) : (
                <Button size="xs" color="red" loading={run.isPending} onClick={() => execute(false)}>
                  {LABEL[action]} {preview.affectedCount} messages
                </Button>
              )}
            </Stack>
          </Alert>
        ) : null}
      </Stack>
    </Modal>
  );
}
