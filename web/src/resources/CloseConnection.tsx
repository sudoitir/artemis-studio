import { useState } from 'react';
import { Alert, Button, Group, Modal, Stack, Text } from '@mantine/core';

import {
  useCloseAddressConsumers,
  useCloseNodeTarget,
  useCluster,
  type ConnectionCloseKind,
  type ConnectionCloseView,
} from '../api/client.ts';
import { useCan } from '../auth/useCan.ts';
import { CapabilityGate } from '../shared/CapabilityGate.tsx';
import { gateFor, type GateVerdict } from '../shared/capabilityGate.ts';
import { ConfirmByTyping } from '../shared/ConfirmByTyping.tsx';
import { NodeOutcomeSummary } from '../shared/NodeOutcomeSummary.tsx';
import { elapsedLabel, toServerMs, useServerNow } from '../app/time.ts';

const PERMISSION_LABEL = "Close client connections, sessions and an address's consumers";

/** What "already gone" is called in the outcome rows, instead of the lifecycle wording. */
const ALREADY_GONE = 'already gone';

type NodeKind = Exclude<ConnectionCloseKind, 'address-consumers'>;

const NOUN: Record<NodeKind, string> = {
  connection: 'connection',
  session: 'session',
  consumer: "consumer's connection",
};

/**
 * The row action on the connections, sessions and consumers views (ADR-0057).
 *
 * <p>The finding and the verb it implies sit on the same row: a consumer the
 * broker has marked slow is one click from the disconnect, rather than a change
 * of tool. What is closed is stated before the confirmation can be armed, and it
 * is confirmed against a name a human recognises — the client id, or the remote
 * address — never the opaque connection id an operator cannot check.
 */
export function CloseConnectionAction({
  clusterId,
  kind,
  nodeId,
  nodeName,
  targetId,
  rowLabel,
  /** When the row on screen was fetched, in epoch ms — the action depends on it. */
  fetchedAt,
}: {
  clusterId: string;
  kind: NodeKind;
  nodeId: string;
  nodeName: string;
  targetId: string;
  /** How the row names itself, for the trigger's accessible name. */
  rowLabel: string;
  fetchedAt: number | null;
}) {
  const { can, loading } = useCan();
  const cluster = useCluster(clusterId);
  const [opened, setOpened] = useState(false);

  const permitted = gateFor(
    can('connection:close', clusterId),
    PERMISSION_LABEL,
    cluster.data?.capabilities.managementWrite,
    loading || cluster.isPending,
  );
  // A row the broker gave no identifier for has nothing to aim at. The control
  // still shows, with the reason — a missing button would read as "Studio cannot
  // close connections" rather than "this row cannot be addressed".
  const gate: GateVerdict = targetId
    ? permitted
    : {
        kind: 'blocked',
        reason: `This broker reported no identifier for this ${NOUN[kind]}, so there is nothing to aim a close at.`,
      };

  return (
    <>
      <CapabilityGate verdict={gate}>
        <Button
          size="compact-xs"
          variant="subtle"
          color="red"
          disabled={gate.kind === 'blocked'}
          // Named, not an icon alone: a row of identical glyphs tells a screen
          // reader nothing about which connection it is about to disconnect.
          aria-label={`Close the ${NOUN[kind]} for ${rowLabel}`}
          onClick={() => setOpened(true)}
        >
          Close
        </Button>
      </CapabilityGate>

      <CloseDialog
        clusterId={clusterId}
        kind={kind}
        nodeId={nodeId}
        nodeName={nodeName}
        targetId={targetId}
        fetchedAt={fetchedAt}
        opened={opened}
        onClose={() => setOpened(false)}
      />
    </>
  );
}

/**
 * The destructive flow. It opens on a preview, so the typed confirmation is
 * armed only once the operator has been shown who is about to be disconnected
 * and what happens to the messages that client is holding.
 */
function CloseDialog({
  clusterId,
  kind,
  nodeId,
  nodeName,
  targetId,
  fetchedAt,
  opened,
  onClose,
}: {
  clusterId: string;
  kind: NodeKind;
  nodeId: string;
  nodeName: string;
  targetId: string;
  fetchedAt: number | null;
  opened: boolean;
  onClose: () => void;
}) {
  const close = useCloseNodeTarget(clusterId, kind, nodeId, targetId);
  const [preview, setPreview] = useState<ConnectionCloseView | null>(null);
  const [result, setResult] = useState<ConnectionCloseView | null>(null);
  const [previewFailed, setPreviewFailed] = useState<string | null>(null);
  const now = useServerNow();

  const start = () => {
    setPreview(null);
    setResult(null);
    setPreviewFailed(null);
    close.mutate(
      { dryRun: true },
      { onSuccess: setPreview, onError: (e) => setPreviewFailed(e.message) },
    );
  };

  const dismiss = () => {
    setPreview(null);
    setResult(null);
    setPreviewFailed(null);
    close.reset();
    onClose();
  };

  const settled = result ?? (preview?.alreadyGone ? preview : null);
  const target = preview?.target ?? null;

  return (
    <Modal
      opened={opened}
      onClose={dismiss}
      onEnterTransitionEnd={start}
      title={`Close this ${NOUN[kind]}`}
      size="lg"
    >
      <Stack gap="md">
        <Text size="sm">
          This disconnects a running application from {nodeName}. It cannot be undone from here —
          a healthy client will reconnect on its own, and a wedged one will not.
        </Text>

        {/* The action depends on how current the row is, so the age is stated
            where the decision is made rather than only in the header. */}
        {fetchedAt ? (
          <Text size="xs" c="dimmed">
            This row was read {elapsedLabel(now - toServerMs(fetchedAt))} ago. The check below is taken now,
            against the broker.
          </Text>
        ) : null}

        <div aria-live="polite">
          {close.isPending && !preview && !result ? (
            <Text size="sm" c="dimmed">
              Reading what this would disconnect…
            </Text>
          ) : null}

          {/* An unavailable estimate is stated, never omitted: an absent number
              reads as zero, which is the most dangerous thing to infer here. */}
          {previewFailed ? (
            <Alert color="yellow" variant="light" title="The target could not be read" role="alert">
              {previewFailed} Studio cannot tell you what this would disconnect, so the close is
              not offered until the read succeeds.
            </Alert>
          ) : null}

          {settled?.alreadyGone ? (
            // The sentence is the whole outcome. A per-node summary beside it would
            // add nothing and read as a second, different verdict.
            <Text size="sm">
              Nothing to close — this {NOUN[kind]} had already gone. That is the state you asked
              for, so nothing was done and nothing failed.
            </Text>
          ) : settled ? (
            <NodeOutcomeSummary
              outcome={settled.outcome}
              alreadyLabel={ALREADY_GONE}
              countNoun="in-flight message"
              verbFuture="would return"
              verbPast="returned"
              destructive
            />
          ) : null}

          {target && !result ? <TargetSummary target={target} nodeName={nodeName} /> : null}
        </div>

        {close.isError && !previewFailed ? (
          <Alert color="red" variant="light" title={close.error.title} role="alert">
            {close.error.message}
          </Alert>
        ) : null}

        {settled ? (
          <Group justify="flex-end">
            <Button size="xs" onClick={dismiss}>
              Close
            </Button>
          </Group>
        ) : target ? (
          <ConfirmByTyping
            token={target.confirmToken}
            confirmLabel={`Close this ${NOUN[kind]}`}
            loading={close.isPending}
            disabled={close.isPending}
            onConfirm={() => close.mutate({}, { onSuccess: setResult })}
          />
        ) : null}
      </Stack>
    </Modal>
  );
}

/** Who is about to be disconnected, and what it costs the messages they hold. */
function TargetSummary({
  target,
  nodeName,
}: {
  target: NonNullable<ConnectionCloseView['target']>;
  nodeName: string;
}) {
  const rows: [string, string][] = [
    ['Client id', target.clientId || 'none reported'],
    ['Remote address', target.remoteAddress || 'not reported'],
    ['User', target.user || 'none'],
    ['Protocol', target.protocol || 'unknown'],
    ['Node', nodeName],
    ['Sessions closed with it', target.sessionCount.toLocaleString()],
    ['Consumers closed with it', target.consumerCount.toLocaleString()],
  ];

  return (
    <Stack gap="xs">
      <Stack gap={2}>
        {rows.map(([label, value]) => (
          <Group key={label} gap="xs" justify="space-between" wrap="nowrap">
            <Text size="xs" c="dimmed">
              {label}
            </Text>
            <Text size="xs">{value}</Text>
          </Group>
        ))}
      </Stack>

      {/* Stated before the confirmation, never discovered afterwards from a DLQ. */}
      <Alert color="yellow" variant="light" title="Messages in flight return to their queue">
        {target.messagesInTransit == null ? (
          <>
            This broker did not report how many messages this client is holding. Any that are in
            flight return to their queue with an increased delivery count, which can push a
            message past its maximum delivery attempts and into the dead-letter queue.
          </>
        ) : (
          <>
            {target.messagesInTransit.toLocaleString()} in-flight message
            {target.messagesInTransit === 1 ? '' : 's'} will return to their queue with an
            increased delivery count. A message already near its maximum delivery attempts can be
            moved to the dead-letter queue by that increase.
          </>
        )}
      </Alert>
    </Stack>
  );
}

/** The addresses view's row action: the trigger and its gate, around the dialog below. */
export function CloseAddressConsumersAction({
  clusterId,
  address,
}: {
  clusterId: string;
  address: string;
}) {
  const { can, loading } = useCan();
  const cluster = useCluster(clusterId);
  const [opened, setOpened] = useState(false);

  const gate = gateFor(
    can('connection:close', clusterId),
    PERMISSION_LABEL,
    cluster.data?.capabilities.managementWrite,
    loading || cluster.isPending,
  );

  return (
    <>
      <CapabilityGate verdict={gate}>
        <Button
          size="compact-xs"
          variant="subtle"
          color="red"
          disabled={gate.kind === 'blocked'}
          aria-label={`Close every consumer on ${address}`}
          onClick={() => setOpened(true)}
        >
          Close consumers
        </Button>
      </CapabilityGate>

      <CloseAddressConsumers
        clusterId={clusterId}
        address={address}
        opened={opened}
        onClose={() => setOpened(false)}
      />
    </>
  );
}

/**
 * Closing every consumer connection bound to an address, across the cluster.
 *
 * <p>The one close with an unbounded blast radius, so it is previewed per node
 * and checked against the bulk cap before it can be armed — a disconnect is not
 * exempt from the cap because nothing is deleted: every consumer it closes hands
 * its in-flight messages back to a queue.
 */
export function CloseAddressConsumers({
  clusterId,
  address,
  opened,
  onClose,
}: {
  clusterId: string;
  address: string;
  opened: boolean;
  onClose: () => void;
}) {
  const close = useCloseAddressConsumers(clusterId, address);
  const [preview, setPreview] = useState<ConnectionCloseView | null>(null);
  const [result, setResult] = useState<ConnectionCloseView | null>(null);
  const [previewFailed, setPreviewFailed] = useState<string | null>(null);

  const start = () => {
    setPreview(null);
    setResult(null);
    setPreviewFailed(null);
    close.mutate(
      { dryRun: true },
      { onSuccess: setPreview, onError: (e) => setPreviewFailed(e.message) },
    );
  };

  const dismiss = () => {
    setPreview(null);
    setResult(null);
    setPreviewFailed(null);
    close.reset();
    onClose();
  };

  const overCap = preview?.outcome.overCap ?? false;

  return (
    <Modal
      opened={opened}
      onClose={dismiss}
      onEnterTransitionEnd={start}
      title={`Close every consumer on ${address}`}
      size="lg"
    >
      <Stack gap="md">
        <Text size="sm">
          This disconnects every application consuming from {address}, on every live node. The
          messages those consumers hold return to their queues with an increased delivery count.
        </Text>

        <div aria-live="polite">
          {close.isPending && !preview ? (
            <Text size="sm" c="dimmed">
              Counting the consumers on each node…
            </Text>
          ) : null}

          {previewFailed ? (
            <Alert color="yellow" variant="light" title="The count could not be taken" role="alert">
              {previewFailed} The close can still proceed, but Studio cannot tell you how many
              consumers it would disconnect.
            </Alert>
          ) : null}

          {preview && !result ? (
            <NodeOutcomeSummary
              outcome={preview.outcome}
              destructive
              alreadyLabel={ALREADY_GONE}
              countNoun="consumer"
              verbFuture="would close"
              verbPast="closed"
            />
          ) : null}
          {result ? (
            <NodeOutcomeSummary
              outcome={result.outcome}
              destructive
              alreadyLabel="no consumers were bound"
              countNoun="consumer"
              verbFuture="would close"
              verbPast="closed"
            />
          ) : null}
        </div>

        {overCap && preview ? (
          <Alert color="yellow" variant="light" title="Over the safety cap">
            This would disconnect {preview.outcome.totalAffected.toLocaleString()} consumers, over
            the cap of {preview.outcome.cap.toLocaleString()}. Confirming will override the cap for
            this operation, and the override is recorded in the audit log.
          </Alert>
        ) : null}

        {close.isError && !previewFailed ? (
          <Alert color="red" variant="light" title={close.error.title} role="alert">
            {close.error.message}
          </Alert>
        ) : null}

        {result ? (
          <Group justify="flex-end">
            <Button size="xs" onClick={dismiss}>
              Close
            </Button>
          </Group>
        ) : (
          <ConfirmByTyping
            token={address}
            confirmLabel={overCap ? 'Close them anyway, over the cap' : 'Close these consumers'}
            loading={close.isPending && preview !== null}
            disabled={close.isPending}
            onConfirm={() => close.mutate({ override: overCap }, { onSuccess: setResult })}
          />
        )}
      </Stack>
    </Modal>
  );
}
