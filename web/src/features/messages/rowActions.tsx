import { useState } from 'react';
import { Alert, Button, Group, Modal, Stack, Text, TextInput } from '@mantine/core';
import { IconArrowBackUp, IconArrowsRightLeft, IconClipboard, IconFileText, IconLink, IconMail, IconTrash } from '@tabler/icons-react';
import { useNavigate } from '@tanstack/react-router';

import { useCan } from '../../kernel/auth/useCan.ts';
import { useCluster } from '../clusters/index.ts';
import type { ActionProps, HostedDialogProps, MessageTarget, QueueTarget } from '../../kernel/actions/types.ts';
import { absoluteHref, clusterHref } from '../../kernel/routing/href.ts';
import { ActionMenuItem } from '../../ui/ActionMenuItem.tsx';
import { gateFor, type GateVerdict } from '../../ui/capabilityGate.ts';
import { ConfirmByTyping } from '../../ui/ConfirmByTyping.tsx';
import { useMessageAction, type AffectedView, type PartialView } from './api.ts';

/** "Browse messages" on a queue's row: the message browser for that queue. */
export function BrowseQueueMessages({ clusterId, target }: ActionProps<QueueTarget>) {
  const navigate = useNavigate();
  const { can, loading } = useCan();
  const gate = gateFor(can('message:read', clusterId), 'Browse messages', undefined, loading);
  const path = `queues/${encodeURIComponent(target.queueName)}/messages`;
  return (
    <ActionMenuItem
      label="Browse messages"
      icon={<IconMail size={16} aria-hidden />}
      verdict={gate}
      href={clusterHref(clusterId, path)}
      onSelect={() => navigate({ to: `/clusters/${clusterId}/${path}` })}
    />
  );
}

/** Where one message opens: its queue's browser with the message's detail, on the node browsed. */
function messagePath(target: MessageTarget) {
  return `queues/${encodeURIComponent(target.queueName)}/messages`;
}

function messageSearch(target: MessageTarget) {
  return { message: String(target.messageId), node: target.node };
}

export function OpenMessage({ clusterId, target }: ActionProps<MessageTarget>) {
  const navigate = useNavigate();
  return (
    <ActionMenuItem
      label="Open details"
      icon={<IconFileText size={16} aria-hidden />}
      href={clusterHref(clusterId, messagePath(target), messageSearch(target))}
      onSelect={() =>
        navigate({
          to: `/clusters/${clusterId}/${messagePath(target)}`,
          search: (prev: Record<string, unknown>) => ({ ...prev, ...messageSearch(target) }),
        } as never)
      }
    />
  );
}

export function CopyMessage({ clusterId, target, host }: ActionProps<MessageTarget>) {
  return (
    <>
      <ActionMenuItem
        label="Copy message id"
        icon={<IconClipboard size={16} aria-hidden />}
        onSelect={() => host.copy(String(target.messageId), 'message id')}
      />
      <ActionMenuItem
        label="Copy link to this message"
        icon={<IconLink size={16} aria-hidden />}
        onSelect={() =>
          host.copy(absoluteHref(clusterHref(clusterId, messagePath(target), messageSearch(target))), 'link to the message')
        }
      />
    </>
  );
}

type OneAction = 'move' | 'retry' | 'delete';

const ONE: Record<OneAction, { verb: string; permission: string; label: string; what: string }> = {
  move: { verb: 'Move', permission: 'message:move', label: 'Move or retry messages', what: 'moving this message' },
  retry: { verb: 'Retry', permission: 'message:move', label: 'Move or retry messages', what: 'retrying this message' },
  delete: { verb: 'Delete', permission: 'message:delete', label: 'Delete or expire messages', what: 'deleting this message' },
};

function useMessageGate(clusterId: string, action: OneAction): GateVerdict {
  const { can, loading } = useCan();
  const cluster = useCluster(clusterId);
  return gateFor(
    can(ONE[action].permission, clusterId),
    ONE[action].label,
    cluster.data?.capabilities.managementWrite,
    loading || cluster.isPending,
  );
}

/**
 * One message moved, retried or deleted by id, from its row. The outcome stays in the dialog —
 * done, not found (the message was consumed or moved since the page was read), or failed with its
 * cause — rather than in a toast that disappears. A delete is confirmed by typing the message id.
 */
function OneMessageDialog({
  opened,
  onClose,
  clusterId,
  target,
  action,
}: HostedDialogProps & { clusterId: string; target: MessageTarget; action: OneAction }) {
  const run = useMessageAction(clusterId, target.queueName);
  const [destination, setDestination] = useState('');
  const [result, setResult] = useState<AffectedView | PartialView | null>(null);
  const one = ONE[action];
  const affected = result && 'affectedCount' in result ? result.affectedCount : null;
  const submit = () =>
    run.mutate(
      {
        action,
        body: { messageIds: [target.messageId], targetQueue: action === 'move' ? destination : undefined },
        node: target.node,
      },
      { onSuccess: (r) => setResult(r as AffectedView | PartialView) },
    );

  return (
    <Modal opened={opened} onClose={onClose} title={`${one.verb} message ${target.messageId}`}>
      <Stack gap="sm">
        <Text size="sm">
          {action === 'move'
            ? `Moves this one message from ${target.queueName} to the queue you name, on the node it was read from.`
            : action === 'retry'
              ? 'Sends this dead-lettered message back to the queue it originally came from.'
              : `Removes this one message from ${target.queueName}. It cannot be brought back.`}
        </Text>
        {action === 'move' && !result ? (
          <TextInput
            label="Target queue"
            description="The queue receives the message on the same node."
            value={destination}
            onChange={(e) => setDestination(e.currentTarget.value)}
            size="xs"
            data-autofocus
          />
        ) : null}
        <div aria-live="polite">
          {run.isPending ? <Text size="sm">{one.verb === 'Retry' ? 'Retrying' : `${one.verb.slice(0, -1)}ing`}…</Text> : null}
          {run.isError ? (
            <Alert color="red" variant="light" title={run.error.title} role="alert">
              {run.error.message} Nothing was changed; check the node in Topology and try again.
            </Alert>
          ) : null}
          {result ? (
            affected === 1 ? (
              <Text size="sm">Done: the message was {action === 'delete' ? 'deleted' : action === 'move' ? 'moved' : 'retried'}.</Text>
            ) : (
              <Alert color="yellow" variant="light" title="Nothing was changed">
                The message was not found on the node. It may have been consumed, expired or moved since this
                page was read.
              </Alert>
            )
          ) : null}
        </div>
        {result ? (
          <Group justify="flex-end">
            <Button size="xs" onClick={onClose}>
              Close
            </Button>
          </Group>
        ) : action === 'delete' ? (
          <ConfirmByTyping
            token={String(target.messageId)}
            confirmLabel="Delete this message"
            loading={run.isPending}
            disabled={run.isPending}
            onConfirm={submit}
          />
        ) : (
          <Group justify="flex-end">
            <Button size="xs" variant="default" onClick={onClose}>
              Cancel
            </Button>
            <Button
              size="xs"
              loading={run.isPending}
              disabled={action === 'move' && !destination.trim()}
              onClick={submit}
            >
              {one.verb} message
            </Button>
          </Group>
        )}
        {action === 'move' && !destination.trim() && !result ? (
          <Text size="xs" c="dimmed">
            Name the target queue to move the message.
          </Text>
        ) : null}
      </Stack>
    </Modal>
  );
}

function OneMessageItem({
  clusterId,
  target,
  host,
  action,
  icon,
}: ActionProps<MessageTarget> & { action: OneAction; icon: React.ReactNode }) {
  const gate = useMessageGate(clusterId, action);
  return (
    <ActionMenuItem
      label={`${ONE[action].verb}…`}
      icon={icon}
      tone={action === 'delete' ? 'danger' : undefined}
      verdict={gate}
      onExplain={(verdict) => host.explain(verdict, ONE[action].what)}
      onSelect={() => host.open(OneMessageDialog, { clusterId, target, action })}
    />
  );
}

export const MoveMessage = (p: ActionProps<MessageTarget>) => (
  <OneMessageItem {...p} action="move" icon={<IconArrowsRightLeft size={16} aria-hidden />} />
);
export const RetryMessage = (p: ActionProps<MessageTarget>) => (
  <OneMessageItem {...p} action="retry" icon={<IconArrowBackUp size={16} aria-hidden />} />
);
export const DeleteMessage = (p: ActionProps<MessageTarget>) => (
  <OneMessageItem {...p} action="delete" icon={<IconTrash size={16} aria-hidden />} />
);
