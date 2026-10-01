import { useRef, useState } from 'react';
import { Stack, Text, TextInput } from '@mantine/core';
import {
  IconArrowBackUp,
  IconArrowsRightLeft,
  IconClipboard,
  IconFileText,
  IconLink,
  IconMail,
  IconTrash,
} from '@tabler/icons-react';
import { useNavigate } from '@tanstack/react-router';

import { useCan } from '../../kernel/auth/useCan.ts';
import type { ActionProps, HostedDialogProps, MessageTarget, QueueTarget } from '../../kernel/actions/types.ts';
import { absoluteHref, clusterHref } from '../../kernel/routing/href.ts';
import { ActionMenuItem } from '../../ui/ActionMenuItem.tsx';
import { gateFor } from '../../ui/capabilityGate.ts';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { notify } from '../../ui/notify.ts';
import { useMessageAction } from './api.ts';
import { useActionGate } from './gates.ts';
import { announceFailure, VERBS } from './outcomes.ts';

/** "Browse messages" on a queue's row: the message browser for that queue. */
export function BrowseQueueMessages({ clusterId, target }: Readonly<ActionProps<QueueTarget>>) {
  const navigate = useNavigate();
  const { can, loading } = useCan();
  const gate = gateFor(can('message:read', clusterId), 'Browse messages', undefined, loading);
  const path = `queues/${encodeURIComponent(target.queueName)}/messages`;
  return (
    <ActionMenuItem
      label="Browse messages"
      icon={<IconMail size="1rem" aria-hidden />}
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

export function OpenMessage({ clusterId, target }: Readonly<ActionProps<MessageTarget>>) {
  const navigate = useNavigate();
  return (
    <ActionMenuItem
      label="Open details"
      icon={<IconFileText size="1rem" aria-hidden />}
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

export function CopyMessage({ clusterId, target, host }: Readonly<ActionProps<MessageTarget>>) {
  return (
    <>
      <ActionMenuItem
        label="Copy message id"
        icon={<IconClipboard size="1rem" aria-hidden />}
        onSelect={() => host.copy(String(target.messageId), 'message id')}
      />
      <ActionMenuItem
        label="Copy link to this message"
        icon={<IconLink size="1rem" aria-hidden />}
        onSelect={() =>
          host.copy(
            absoluteHref(clusterHref(clusterId, messagePath(target), messageSearch(target))),
            'link to the message',
          )
        }
      />
    </>
  );
}

type OneAction = 'move' | 'retry' | 'delete';

const ONE: Record<OneAction, { what: string; confirmLabel: string; intro: (queueName: string) => string }> = {
  move: {
    what: 'moving this message',
    confirmLabel: 'Move message',
    intro: (queueName) =>
      `Moves this one message from ${queueName} to the queue you name, on the node it was read from.`,
  },
  retry: {
    what: 'retrying this message',
    confirmLabel: 'Retry message',
    intro: () => 'Sends this dead-lettered message back to the queue it originally came from.',
  },
  delete: {
    what: 'deleting this message',
    confirmLabel: 'Delete this message',
    intro: (queueName) => `Removes this one message from ${queueName}. It cannot be brought back.`,
  },
};

/** The one message was not there to act on: consumed, expired or moved since the page was read. */
const NOT_FOUND =
  'The message was not found on the node. It may have been consumed, expired or moved since this page was read.';

/**
 * One message moved, retried or deleted by id, from its row. It confirms with what it does; a
 * delete is armed by typing the message id. The outcome is announced: done, not found (nothing was
 * changed), or failed with its cause. A move names its target, checked on activation.
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
  const [destinationError, setDestinationError] = useState<string | null>(null);
  const destinationRef = useRef<HTMLInputElement>(null);
  const one = ONE[action];
  const subject = `message ${target.messageId} in queue "${target.queueName}"`;

  const submit = () => {
    if (action === 'move' && !destination.trim()) {
      setDestinationError('Name the target queue to move the message.');
      destinationRef.current?.focus();
      return;
    }
    run.mutate(
      {
        action,
        body: { messageIds: [target.messageId], targetQueue: action === 'move' ? destination : undefined },
        node: target.node,
      },
      {
        onSuccess: (r) => {
          if ('cap' in r) return;
          if ('notDone' in r || r.affectedCount !== 1) {
            notify.failed({
              action: VERBS[action],
              subject,
              cause: 'notDone' in r ? r.error : NOT_FOUND,
              next: 'Nothing was changed. Reload the queue to see what is there now.',
            });
          } else {
            notify.succeeded({ action: VERBS[action], subject });
          }
          onClose();
        },
        onError: (e) => announceFailure(action, subject, e),
      },
    );
  };

  return (
    <ConfirmDialog
      opened={opened}
      onClose={onClose}
      title={`${VERBS[action].verb} message ${target.messageId}`}
      consequence={
        <Stack gap="sm">
          <Text size="sm">{one.intro(target.queueName)}</Text>
          {action === 'move' ? (
            <TextInput
              ref={destinationRef}
              label="Target queue"
              description="The queue receives the message on the same node."
              value={destination}
              error={destinationError}
              onChange={(e) => {
                setDestination(e.currentTarget.value);
                setDestinationError(null);
              }}
              onBlur={() =>
                setDestinationError(destination.trim() ? null : 'Name the target queue to move the message.')
              }
              size="xs"
              data-autofocus
            />
          ) : null}
        </Stack>
      }
      confirmLabel={one.confirmLabel}
      tone={action === 'delete' ? 'danger' : 'default'}
      typedName={action === 'delete' ? String(target.messageId) : undefined}
      pending={run.isPending}
      onConfirm={submit}
    />
  );
}

function OneMessageItem({
  clusterId,
  target,
  host,
  action,
  icon,
}: ActionProps<MessageTarget> & { action: OneAction; icon: React.ReactNode }) {
  const gate = useActionGate(clusterId, action);
  return (
    <ActionMenuItem
      label={`${VERBS[action].verb}…`}
      icon={icon}
      tone={action === 'delete' ? 'danger' : undefined}
      verdict={gate}
      onExplain={(verdict) => host.explain(verdict, ONE[action].what)}
      onSelect={() => host.open(OneMessageDialog, { clusterId, target, action })}
    />
  );
}

export const MoveMessage = (p: ActionProps<MessageTarget>) => (
  <OneMessageItem {...p} action="move" icon={<IconArrowsRightLeft size="1rem" aria-hidden />} />
);
export const RetryMessage = (p: ActionProps<MessageTarget>) => (
  <OneMessageItem {...p} action="retry" icon={<IconArrowBackUp size="1rem" aria-hidden />} />
);
export const DeleteMessage = (p: ActionProps<MessageTarget>) => (
  <OneMessageItem {...p} action="delete" icon={<IconTrash size="1rem" aria-hidden />} />
);
