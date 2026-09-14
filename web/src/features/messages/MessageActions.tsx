import { useState } from 'react';
import { Alert, Button, Group, Menu, Modal, Paper, Stack, Text, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';

import { useMessageAction, type MessageActionKind, type PartialView } from './api.ts';
import { BulkActionPreview } from './BulkActionPreview.tsx';

const LABEL: Record<MessageActionKind, string> = {
  move: 'Move',
  retry: 'Retry',
  delete: 'Delete',
  expire: 'Expire',
};

/**
 * Sticky action bar shown when the selection is non-empty (by-id ops), plus a
 * "By selector…" entry point into {@link BulkActionPreview}. By-id ops are under
 * the cap by construction (the id list is the count); each still confirms.
 */
export function MessageActions({
  clusterId,
  queueName,
  node,
  selected,
  onCleared,
}: {
  clusterId: string;
  queueName: string;
  node?: string;
  selected: ReadonlySet<string>;
  onCleared: () => void;
}) {
  const run = useMessageAction(clusterId, queueName);
  const [confirm, setConfirm] = useState<MessageActionKind | null>(null);
  const [target, setTarget] = useState('');
  const [bulk, setBulk] = useState<MessageActionKind | null>(null);
  const [partial, setPartial] = useState<PartialView | null>(null);

  const ids = [...selected].map(Number).filter((n) => Number.isFinite(n));

  const doAction = (action: MessageActionKind) => {
    run.mutate(
      {
        action,
        body: { messageIds: ids, targetQueue: action === 'move' ? target : undefined },
        node,
      },
      {
        onSuccess: (r) => {
          if ('notDone' in r) {
            // Partial is the outcome that matters most: the done part cannot be undone, and
            // the operator has to see which ids are still where they were. It stays in the
            // dialog rather than in a toast that disappears.
            setPartial(r);
            return;
          }
          notifications.show({
            message: `${LABEL[action]}d ${'affectedCount' in r ? r.affectedCount : ids.length} messages`,
          });
          setConfirm(null);
          setTarget('');
          onCleared();
        },
        onError: (e) => notifications.show({ color: 'red', message: e.message }),
      },
    );
  };

  return (
    <>
      <Group justify="space-between">
        <Menu>
          <Menu.Target>
            <Button size="xs" variant="light">
              By selector…
            </Button>
          </Menu.Target>
          <Menu.Dropdown>
            {(['move', 'retry', 'delete', 'expire'] as const).map((a) => (
              <Menu.Item key={a} onClick={() => setBulk(a)}>
                {LABEL[a]} by selector
              </Menu.Item>
            ))}
          </Menu.Dropdown>
        </Menu>
        {selected.size > 0 ? (
          <Paper withBorder p="xs" shadow="sm">
            <Group gap="xs">
              <Text size="xs" fw={600}>
                {selected.size} selected
              </Text>
              {(['move', 'retry', 'delete', 'expire'] as const).map((a) => (
                <Button key={a} size="xs" variant="default" onClick={() => setConfirm(a)}>
                  {LABEL[a]}
                </Button>
              ))}
              <Button size="xs" variant="subtle" onClick={onCleared}>
                Clear
              </Button>
            </Group>
          </Paper>
        ) : null}
      </Group>

      <Modal
        opened={confirm !== null}
        onClose={() => {
          if (run.isPending) return;
          setConfirm(null);
          if (partial) {
            setPartial(null);
            onCleared();
          }
        }}
        title={confirm ? `${LABEL[confirm]} ${selected.size} messages?` : ''}
        size="md"
      >
        <Stack gap="sm">
          {partial && confirm ? (
            <Alert
              color="yellow"
              variant="light"
              role="alert"
              title={`${LABEL[confirm]}d ${partial.affectedCount} of ${ids.length} messages, then stopped`}
            >
              {partial.error} Not {LABEL[confirm].toLowerCase()}d: {partial.notDone.join(', ')}. Close this
              and select them again to retry.
            </Alert>
          ) : null}
          {confirm === 'move' ? (
            <TextInput
              label="Target queue"
              value={target}
              onChange={(e) => setTarget(e.currentTarget.value)}
              size="xs"
            />
          ) : null}
          <Text size="sm">
            This acts on the {selected.size} selected message{selected.size === 1 ? '' : 's'} by id.
          </Text>
          <Group justify="flex-end">
            <Button variant="default" size="xs" onClick={() => setConfirm(null)}>
              Cancel
            </Button>
            <Button
              size="xs"
              color="red"
              loading={run.isPending}
              disabled={(confirm === 'move' && !target) || partial !== null}
              onClick={() => confirm && doAction(confirm)}
            >
              {confirm ? LABEL[confirm] : ''}
            </Button>
          </Group>
        </Stack>
      </Modal>

      {bulk ? (
        <BulkActionPreview
          clusterId={clusterId}
          queueName={queueName}
          action={bulk}
          node={node}
          opened={bulk !== null}
          onClose={() => setBulk(null)}
          onDone={() => {
            setBulk(null);
            onCleared();
          }}
        />
      ) : null}
    </>
  );
}
