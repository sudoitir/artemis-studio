import { useEffect, useState, type ReactNode } from 'react';
import { Alert, Button, Group, Modal, Stack, Text } from '@mantine/core';
import {
  IconClipboard,
  IconEdit,
  IconLink,
  IconListDetails,
  IconPlayerPause,
  IconPlayerPlay,
  IconTrash,
} from '@tabler/icons-react';
import { Link, useNavigate } from '@tanstack/react-router';

import { useCluster } from '../clusters/index.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import type {
  ActionProps,
  AddressTarget,
  ConsumerTarget,
  HostedDialogProps,
  LinkProps,
  ProducerTarget,
  QueueTarget,
} from '../../kernel/actions/types.ts';
import { absoluteHref, clusterHref } from '../../kernel/routing/href.ts';
import { queueHref } from './queueHref.ts';
import { ActionMenuItem } from '../../ui/ActionMenuItem.tsx';
import { gateFor, type GateVerdict } from '../../ui/capabilityGate.ts';
import { NodeOutcomeSummary } from '../../ui/NodeOutcomeSummary.tsx';
import { useQueue, useSetQueuePaused, type LifecycleOutcomeView, type QueueView } from './api.ts';
import { EditQueueForm } from './EditQueueForm.tsx';
import { DeleteQueueDialog } from './QueueLifecycleActions.tsx';
import linkClasses from '../../ui/InlineLink.module.css';

/** Whether a management write on this cluster may be attempted, and why not (non-negotiable #5). */
function useWriteGate(clusterId: string, permission: string, label: string): GateVerdict {
  const { can, loading } = useCan();
  const cluster = useCluster(clusterId);
  return gateFor(
    can(permission, clusterId),
    label,
    cluster.data?.capabilities.managementWrite,
    loading || cluster.isPending,
  );
}

/**
 * A hosted dialog that needs the queue's row. The row comes with the target when the menu was
 * opened from the queues grid; elsewhere it is looked up by name, and a queue that is gone is
 * stated rather than opening a dialog about nothing. The inner dialog is opened a frame after the
 * row arrives, so its `onEnterTransitionEnd` preview still runs.
 */
function WithQueue({
  opened,
  onClose,
  clusterId,
  target,
  render,
}: HostedDialogProps & {
  clusterId: string;
  target: QueueTarget;
  render: (queue: QueueView, dialog: HostedDialogProps) => ReactNode;
}) {
  const { queue, isPending, isError } = useQueue(clusterId, target.queueName, target.snapshot);
  const [ready, setReady] = useState(false);
  useEffect(() => {
    if (!queue || !opened) return;
    const frame = requestAnimationFrame(() => setReady(true));
    return () => cancelAnimationFrame(frame);
  }, [queue, opened]);

  if (queue) return <>{render(queue, { opened: opened && ready, onClose })}</>;
  return (
    <Modal opened={opened} onClose={onClose} title={target.queueName}>
      <Stack gap="sm" aria-live="polite">
        {isPending ? (
          <Text size="sm">Looking the queue up…</Text>
        ) : (
          <Alert color="yellow" variant="light" title={isError ? 'The queue could not be read' : 'The queue is not there'}>
            {isError
              ? 'Studio could not read the queue list just now. Try again in a moment.'
              : `No queue named ${target.queueName} is on this cluster now. It may have been deleted since this view was loaded.`}
          </Alert>
        )}
        <Group justify="flex-end">
          <Button size="xs" variant="default" onClick={onClose}>
            Close
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}

/**
 * Pause or resume one queue on every live node, from a menu. Reversible, so it is confirmed once;
 * its per-node outcome — including a partial one — stays until dismissed, because the menu that
 * started it is gone.
 */
function PauseQueueDialog({
  opened,
  onClose,
  clusterId,
  queue,
}: HostedDialogProps & { clusterId: string; queue: QueueView }) {
  const setPaused = useSetQueuePaused(clusterId, queue.queueName);
  const [outcome, setOutcome] = useState<LifecycleOutcomeView | null>(null);
  const paused = queue.perNode.some((n) => n.paused);
  const verb = paused ? 'Resume' : 'Pause';

  return (
    <Modal opened={opened} onClose={onClose} title={`${verb} ${queue.queueName}`}>
      <Stack gap="md">
        <Text size="sm">
          {paused
            ? 'Resuming restarts delivery to this queue’s consumers on every live node.'
            : 'Pausing stops delivery to this queue’s consumers on every live node. Messages keep arriving and wait; nothing is lost. Resume to deliver them.'}
        </Text>
        <div aria-live="polite">
          {setPaused.isPending ? <Text size="sm">{paused ? 'Resuming' : 'Pausing'} on every live node…</Text> : null}
          {setPaused.isError ? (
            <Alert color="red" variant="light" title={setPaused.error.title} role="alert">
              {setPaused.error.message} Nothing was changed where the request failed; try again, or check the
              node in Topology.
            </Alert>
          ) : null}
          {outcome ? <NodeOutcomeSummary outcome={outcome} /> : null}
        </div>
        <Group justify="flex-end">
          {outcome ? (
            <Button size="xs" onClick={onClose}>
              Close
            </Button>
          ) : (
            <>
              <Button size="xs" variant="default" onClick={onClose}>
                Cancel
              </Button>
              <Button
                size="xs"
                loading={setPaused.isPending}
                onClick={() => setPaused.mutate({ paused: !paused }, { onSuccess: setOutcome })}
              >
                {verb} queue
              </Button>
            </>
          )}
        </Group>
      </Stack>
    </Modal>
  );
}

export function OpenQueue({ clusterId, target }: ActionProps<QueueTarget>) {
  const navigate = useNavigate();
  return (
    <ActionMenuItem
      label="Open details"
      icon={<IconListDetails size={16} aria-hidden />}
      href={queueHref(clusterId, target.queueName)}
      onSelect={() => navigate({ to: `/clusters/${clusterId}/queues`, search: { queue: target.queueName } })}
    />
  );
}

export function CopyQueueName({ target, host }: ActionProps<QueueTarget>) {
  return (
    <ActionMenuItem
      label="Copy queue name"
      icon={<IconClipboard size={16} aria-hidden />}
      onSelect={() => host.copy(target.queueName, 'queue name')}
    />
  );
}

export function CopyQueueLink({ clusterId, target, host }: ActionProps<QueueTarget>) {
  return (
    <ActionMenuItem
      label="Copy link to this queue"
      icon={<IconLink size={16} aria-hidden />}
      onSelect={() => host.copy(absoluteHref(queueHref(clusterId, target.queueName)), 'link to the queue')}
    />
  );
}

export function PauseResumeQueue({ clusterId, target, host }: ActionProps<QueueTarget>) {
  const gate = useWriteGate(clusterId, 'queue:pause', 'Pause and resume queues');
  const { queue } = useQueue(clusterId, target.queueName, target.snapshot);
  const paused = queue ? queue.perNode.some((n) => n.paused) : undefined;
  const label = paused === undefined ? 'Pause or resume…' : paused ? 'Resume…' : 'Pause…';
  return (
    <ActionMenuItem
      label={label}
      icon={paused ? <IconPlayerPlay size={16} aria-hidden /> : <IconPlayerPause size={16} aria-hidden />}
      verdict={gate}
      onExplain={(verdict) => host.explain(verdict, 'pausing this queue')}
      onSelect={() =>
        host.open(WithQueue, {
          clusterId,
          target,
          render: (q, dialog) => <PauseQueueDialog {...dialog} clusterId={clusterId} queue={q} />,
        })
      }
    />
  );
}

export function EditQueue({ clusterId, target, host }: ActionProps<QueueTarget>) {
  const gate = useWriteGate(clusterId, 'queue:update', "Change a queue's configuration");
  return (
    <ActionMenuItem
      label="Edit configuration…"
      icon={<IconEdit size={16} aria-hidden />}
      verdict={gate}
      onExplain={(verdict) => host.explain(verdict, 'editing this queue')}
      onSelect={() =>
        host.open(WithQueue, {
          clusterId,
          target,
          render: (q, dialog) => <EditQueueForm {...dialog} clusterId={clusterId} queue={q} />,
        })
      }
    />
  );
}

export function DeleteQueue({ clusterId, target, host }: ActionProps<QueueTarget>) {
  const gate = useWriteGate(clusterId, 'queue:delete', 'Destroy queues and addresses');
  return (
    <ActionMenuItem
      label="Delete queue…"
      icon={<IconTrash size={16} aria-hidden />}
      tone="danger"
      verdict={gate}
      onExplain={(verdict) => host.explain(verdict, 'deleting this queue')}
      onSelect={() =>
        host.open(WithQueue, {
          clusterId,
          target,
          render: (q, dialog) => (
            <DeleteQueueDialog {...dialog} clusterId={clusterId} queue={q} onDeleted={() => {}} />
          ),
        })
      }
    />
  );
}

/** A queue's name as a link to its detail (ADR-0105). */
export function QueueLink({ clusterId, target, children }: LinkProps<QueueTarget>) {
  return (
    <Link to={`/clusters/${clusterId}/queues`} search={{ queue: target.queueName }} className={linkClasses.link}>
      {children}
    </Link>
  );
}

/** On a consumer's row: the queue it consumes from. */
export function ConsumerOpenQueue({ clusterId, target }: ActionProps<ConsumerTarget>) {
  const navigate = useNavigate();
  const name = target.queueName;
  return (
    <ActionMenuItem
      label={name ? `Open queue ${name}` : 'Open its queue'}
      icon={<IconListDetails size={16} aria-hidden />}
      verdict={name ? undefined : { kind: 'blocked', reason: 'The broker reported no queue for this consumer.' }}
      href={name ? queueHref(clusterId, name) : undefined}
      onSelect={() => name && navigate({ to: `/clusters/${clusterId}/queues`, search: { queue: name } })}
    />
  );
}

/** The queues bound to an address, from any row that names one. */
function OpenQueuesOn({ clusterId, address }: { clusterId: string; address: string | null | undefined }) {
  const navigate = useNavigate();
  return (
    <ActionMenuItem
      label="Open its queues"
      icon={<IconListDetails size={16} aria-hidden />}
      verdict={address ? undefined : { kind: 'blocked', reason: 'The broker reported no address for this row.' }}
      href={address ? clusterHref(clusterId, 'queues', { q: address }) : undefined}
      onSelect={() => address && navigate({ to: `/clusters/${clusterId}/queues`, search: { q: address } })}
    />
  );
}

export function AddressOpenQueues({ clusterId, target }: ActionProps<AddressTarget>) {
  return <OpenQueuesOn clusterId={clusterId} address={target.address} />;
}

export function ProducerOpenQueues({ clusterId, target }: ActionProps<ProducerTarget>) {
  return <OpenQueuesOn clusterId={clusterId} address={target.address} />;
}
