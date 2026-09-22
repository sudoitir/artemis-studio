import { useRef, useState } from 'react';
import {
  Alert,
  Button,
  Checkbox,
  Code,
  Group,
  Modal,
  SegmentedControl,
  Select,
  Stack,
  Text,
  Title,
} from '@mantine/core';
import { useNavigate } from '@tanstack/react-router';

import { useCluster, useClusters } from '../clusters/index.ts';
import { AddressPicker } from '../queues/index.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import type { MessageSelection } from '../../kernel/slots.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { gateFor } from '../../ui/capabilityGate.ts';
import { ConfirmByTyping } from '../../ui/ConfirmByTyping.tsx';
import { useTransferExecute, useTransferPreview, type Finding, type TransferMode, type TransferRunView } from './api.ts';
import { endpointsOf, nodeOptions, serving } from './nodes.ts';
import { bytes, MODE, plural } from './words.ts';

const PERMISSION: Record<TransferMode, { id: string; label: string }> = {
  MOVE: { id: 'message:move', label: 'Move or retry messages' },
  COPY: { id: 'message:read', label: 'Browse messages' },
};

function selectionWords(selection: MessageSelection, queueName: string, total: number | null): string {
  switch (selection.kind) {
    case 'ids':
      return `the ${plural(selection.ids.length, 'selected message')} of ${queueName}`;
    case 'filter':
      return `every message of ${queueName} matching ${selection.filter}`;
    case 'all':
      return total == null ? `every message of ${queueName}` : `all ${plural(total, 'message')} of ${queueName}`;
  }
}

/** What the run would do, as one sentence an operator can check before arming it. */
function blastRadius(run: TransferRunView, clusterName: (id: string) => string): string {
  const { source, target } = run;
  const what =
    run.estimate == null
      ? 'the selected messages (how many is not known until the run counts them)'
      : `${plural(run.estimate, 'message')} (${bytes(run.estimateBytes)})`;
  const where =
    `from ${source.queue} on ${source.nodeName} (${clusterName(source.clusterId)}) ` +
    `to ${target.queue} on ${target.nodeName} (${clusterName(target.clusterId)})`;
  return run.mode === 'MOVE'
    ? `Move ${what} ${where}. They leave the source queue.`
    : `Copy ${what} ${where}. The source queue is unchanged.`;
}

const GROUPS: { kind: Finding['kind']; title: string; tone?: string }[] = [
  { kind: 'REFUSE', title: 'Refused', tone: 'var(--as-danger)' },
  { kind: 'WARN', title: 'Needs your acknowledgement', tone: 'var(--as-warning)' },
  { kind: 'UNKNOWN', title: 'Could not be checked, so not counted as passing' },
];

function Snippet({ snippet }: { snippet?: string | null }) {
  if (!snippet) return null;
  return (
    <>
      <Text size="xs" fw={600}>
        Add this to <Code>broker.xml</Code>:
      </Text>
      <Code block>{snippet}</Code>
    </>
  );
}

/**
 * Destination → preview → confirm for one message transfer (ADR-0097). Nothing touches a broker
 * until the frozen plan is confirmed: the preview states the blast radius and every acceptance
 * finding, a warning is acknowledged one by one, and a move, or anything over the safety cap, is
 * armed by typing the source queue's name. Once the run is accepted the operator is taken to it.
 *
 * `redistribute` fixes the target to the same queue in the same cluster, on another node.
 */
export function TransferDialog({
  clusterId,
  queueName,
  node,
  selection,
  total,
  redistribute = false,
  opened,
  onClose,
  onStarted,
}: {
  clusterId: string;
  queueName: string;
  node?: string;
  selection: MessageSelection;
  total: number | null;
  redistribute?: boolean;
  opened: boolean;
  onClose: () => void;
  onStarted: () => void;
}) {
  const navigate = useNavigate();
  const { can, loading } = useCan();
  const clusters = useClusters();
  const source = useCluster(clusterId);
  const preview = useTransferPreview(clusterId);
  const execute = useTransferExecute(clusterId);

  const [mode, setMode] = useState<TransferMode>('MOVE');
  const [targetClusterId, setTargetClusterId] = useState<string>(clusterId);
  const [targetNodeId, setTargetNodeId] = useState<string | null>(null);
  const [targetQueue, setTargetQueue] = useState(queueName);
  const [errors, setErrors] = useState<{ node?: string; queue?: string }>({});
  const [acked, setAcked] = useState<Set<string>>(new Set());
  const nodeRef = useRef<HTMLInputElement>(null);
  const queueRef = useRef<HTMLInputElement>(null);

  const target = useCluster(targetClusterId);
  const sourceEndpoints = endpointsOf(source.data?.topology);
  const sourceNode = sourceEndpoints.find((e) => e.id === node) ?? sourceEndpoints.find(serving);
  const clusterName = (id: string) => clusters.data?.find((c) => c.id === id)?.name ?? source.data?.name ?? id;

  const close = () => {
    preview.reset();
    execute.reset();
    setMode('MOVE');
    setTargetClusterId(clusterId);
    setTargetNodeId(null);
    setTargetQueue(queueName);
    setErrors({});
    setAcked(new Set());
    onClose();
  };

  const effectiveMode: TransferMode = redistribute ? 'MOVE' : mode;
  const sourceGate = gateFor(
    can(PERMISSION[effectiveMode].id, clusterId),
    PERMISSION[effectiveMode].label,
    effectiveMode === 'MOVE' ? source.data?.capabilities.managementWrite : source.data?.capabilities.managementRead,
    loading || source.isPending,
  );
  const targetGate = gateFor(
    can('message:send', targetClusterId),
    'Send messages',
    target.data?.capabilities.managementRead,
    loading || target.isPending,
  );
  const blocked = sourceGate.kind === 'blocked' ? sourceGate : targetGate.kind === 'blocked' ? targetGate : null;
  const uncertain =
    (sourceGate.kind === 'allowed' && sourceGate.uncertain) || (targetGate.kind === 'allowed' && targetGate.uncertain);

  const targetNodes = nodeOptions(
    endpointsOf(target.data?.topology),
    targetClusterId === clusterId && sourceNode ? { id: sourceNode.id, reason: 'the source node' } : undefined,
  );
  // A redistribution with one other node has nothing to choose; it is chosen for the operator, visibly.
  const choosable = targetNodes.filter((o) => !o.disabled);
  const chosenNode = targetNodeId ?? (redistribute && choosable.length === 1 ? choosable[0].value : null);

  const validate = (field: 'node' | 'queue') => {
    const message =
      field === 'node'
        ? chosenNode
          ? undefined
          : 'Choose the node the messages go to.'
        : targetQueue.trim()
          ? undefined
          : 'Name the queue the messages go to.';
    setErrors((e) => ({ ...e, [field]: message }));
    return message === undefined;
  };

  const takePreview = () => {
    const nodeOk = validate('node');
    const queueOk = redistribute || validate('queue');
    if (!nodeOk) return nodeRef.current?.focus();
    if (!queueOk) return queueRef.current?.focus();
    if (!sourceNode || !chosenNode) return;
    execute.reset();
    setAcked(new Set());
    preview.mutate({
      mode: effectiveMode,
      sourceQueue: queueName,
      sourceNodeId: sourceNode.id,
      selection:
        selection.kind === 'ids'
          ? { kind: 'IDS', ids: selection.ids, filter: null }
          : selection.kind === 'filter'
            ? { kind: 'FILTER', ids: null, filter: selection.filter }
            : { kind: 'ALL', ids: null, filter: null },
      targetClusterId: redistribute ? clusterId : targetClusterId,
      targetNodeId: chosenNode,
      targetQueue: redistribute ? queueName : targetQueue.trim(),
      targetAddress: null,
    });
  };

  const data = preview.data;
  const findings = data?.findings ?? [];
  const refused = findings.some((f) => f.kind === 'REFUSE');
  const warnings = findings.filter((f) => f.kind === 'WARN');
  const unacked = warnings.filter((f) => !acked.has(f.code)).length;
  const typed = data ? data.mode === 'MOVE' || data.overCap : false;
  const confirmLabel = data ? `${MODE[data.mode].verb} ${data.estimate == null ? 'messages' : plural(data.estimate, 'message')}` : '';

  const start = () => {
    if (!data) return;
    execute.mutate(
      {
        runId: data.id,
        body: {
          planHash: data.planHash,
          // Typing the queue name is the override: it is only armed where the preview said so.
          override: data.overCap,
          acknowledged: warnings.map((f) => f.code),
          confirmQueue: data.source.queue,
        },
      },
      {
        onSuccess: (run) => {
          close();
          navigate({ to: `/clusters/${clusterId}/transfers/${run.id}` });
          onStarted();
        },
      },
    );
  };

  const clusterOptions = (clusters.data ?? []).map((c) => {
    const allowed = loading || can('message:send', c.id);
    return {
      value: c.id,
      label: allowed ? c.name : `${c.name}: you do not have the "Send messages" permission here`,
      disabled: !allowed,
    };
  });

  const title = redistribute ? `Redistribute from ${queueName} to another node` : `Transfer messages from ${queueName}`;

  return (
    <Modal opened={opened} onClose={close} title={title} size="xl">
      <Stack gap="md">
        <Text size="sm">
          From {selectionWords(selection, queueName, total)}
          {sourceNode ? ` on ${sourceNode.name}` : ''}.
        </Text>

        {!data ? (
          <Stack gap="sm">
            {!sourceNode && source.data ? (
              <Alert color="yellow" variant="light" title="No source node to read from" role="alert">
                No node of this cluster is live and managed by Studio now, so there is nothing to transfer from.
                Wait for a node to come back, then open this again.
              </Alert>
            ) : null}
            {redistribute ? (
              <Text size="sm">
                The messages move to {queueName} on the node you choose, and land on that node&rsquo;s own queue.
              </Text>
            ) : (
              <>
                <Stack gap={4}>
                  <Text size="sm" fw={500} id="transfer-mode">
                    Mode
                  </Text>
                  <SegmentedControl
                    aria-labelledby="transfer-mode"
                    value={mode}
                    onChange={(v) => setMode(v as TransferMode)}
                    data={[
                      { value: 'MOVE', label: 'Move: the messages leave the source' },
                      { value: 'COPY', label: 'Copy: the source is unchanged' },
                    ]}
                  />
                </Stack>
                <Select
                  label="Target cluster"
                  description="A cluster you may not send to is listed, and says so."
                  data={clusterOptions}
                  value={targetClusterId}
                  allowDeselect={false}
                  onChange={(v) => {
                    if (!v) return;
                    setTargetClusterId(v);
                    setTargetNodeId(null);
                  }}
                />
              </>
            )}
            <Select
              ref={nodeRef}
              label="Target node"
              description="Every node is listed; one that cannot take messages says why."
              placeholder={target.isPending ? 'Reading the cluster’s nodes…' : 'Choose a node'}
              data={targetNodes}
              value={chosenNode}
              onChange={(v) => {
                setTargetNodeId(v);
                if (v) setErrors((e) => ({ ...e, node: undefined }));
              }}
              onBlur={() => validate('node')}
              error={errors.node}
              nothingFoundMessage="This cluster has no nodes Studio knows of."
            />
            {redistribute ? null : (
              <AddressPicker
                clusterId={targetClusterId}
                label="Target queue"
                description="The queue on the target node. A queue that does not exist is checked in the preview."
                value={targetQueue}
                onChange={(v) => {
                  setTargetQueue(v);
                  if (v.trim()) setErrors((e) => ({ ...e, queue: undefined }));
                }}
                onBlur={() => validate('queue')}
                error={errors.queue}
                inputRef={queueRef}
                unknownHint="No queue by that name on this cluster yet. The preview says whether the broker would create it."
              />
            )}
            {uncertain && !blocked ? (
              <Text size="sm">
                Whether these brokers allow Studio&rsquo;s management operations has not been established yet. The
                preview is offered anyway, and states what it could not check.
              </Text>
            ) : null}
            <Group justify="flex-end">
              <Button variant="default" size="xs" onClick={close}>
                Cancel
              </Button>
              <CapabilityGate verdict={blocked ?? { kind: 'allowed', uncertain: false }} what="previewing this transfer">
                <Button
                  size="xs"
                  loading={preview.isPending}
                  disabled={blocked !== null || !sourceNode}
                  onClick={takePreview}
                >
                  Preview
                </Button>
              </CapabilityGate>
            </Group>
          </Stack>
        ) : null}

        <div aria-live="polite">
          {preview.isPending ? (
            <Text size="sm" c="dimmed">
              Reading the source and checking the target…
            </Text>
          ) : null}
          {preview.isError ? (
            <Alert color="red" variant="light" title={preview.error.title} role="alert">
              <Stack gap="xs" align="flex-start">
                <Text size="sm">{preview.error.message}</Text>
                <Text size="sm">Nothing was moved. Change the destination, or preview again.</Text>
              </Stack>
            </Alert>
          ) : null}
          {data ? (
            <Stack gap={4}>
              <Text size="sm" fw={600}>
                {blastRadius(data, clusterName)}
              </Text>
              <Text size="sm">
                {refused
                  ? 'The target cannot accept this transfer. The reasons are below; nothing will run.'
                  : warnings.length > 0
                    ? `The target can accept it, with ${plural(warnings.length, 'warning')} to acknowledge.`
                    : 'The target can accept it.'}
              </Text>
            </Stack>
          ) : null}
        </div>

        {data ? (
          <Stack gap="md">
            {GROUPS.map((group) => {
              const rows = findings.filter((f) => f.kind === group.kind);
              if (rows.length === 0) return null;
              return (
                <Stack key={group.kind} gap="xs">
                  <Title order={5} style={{ color: group.tone }}>
                    {group.title}
                  </Title>
                  {rows.map((f) => (
                    <Stack key={f.code} gap={4}>
                      {f.kind === 'WARN' ? (
                        <Checkbox
                          label={f.words}
                          checked={acked.has(f.code)}
                          onChange={(e) => {
                            const on = e.currentTarget.checked;
                            setAcked((prev) => {
                              const next = new Set(prev);
                              if (on) next.add(f.code);
                              else next.delete(f.code);
                              return next;
                            });
                          }}
                        />
                      ) : (
                        <Text size="sm">{f.words}</Text>
                      )}
                      <Snippet snippet={f.snippet} />
                    </Stack>
                  ))}
                </Stack>
              );
            })}

            {data.notes.length > 0 ? (
              <Stack gap={4}>
                <Title order={5}>Good to know</Title>
                {data.notes.map((n) => (
                  <Text key={n} size="sm">
                    {n}
                  </Text>
                ))}
              </Stack>
            ) : null}

            {!refused && data.overCap ? (
              <Alert color="yellow" variant="light" title="Over the safety cap">
                {data.estimate == null
                  ? `How many messages this selects is not known, so it cannot be held to the safety cap of ${data.cap.toLocaleString()}.`
                  : `This selects ${data.estimate.toLocaleString()} messages, over the safety cap of ${data.cap.toLocaleString()}.`}{' '}
                Typing the queue name below overrides the cap for this run, and the override is recorded in the audit
                log.
              </Alert>
            ) : null}

            {execute.isError ? (
              <Alert color="red" variant="light" title={execute.error.title} role="alert">
                <Stack gap="xs" align="flex-start">
                  <Text size="sm">{execute.error.message}</Text>
                  <Text size="sm">Nothing was run. Preview again to confirm the transfer as it is now.</Text>
                  <Button size="xs" variant="light" onClick={takePreview}>
                    Preview again
                  </Button>
                </Stack>
              </Alert>
            ) : null}

            {refused ? null : (
              <Stack gap="xs">
                {unacked > 0 ? (
                  <Text size="sm">
                    Acknowledge {unacked === 1 ? 'the warning' : `the ${unacked} warnings`} above to run this.
                  </Text>
                ) : null}
                {typed ? (
                  <ConfirmByTyping
                    token={data.source.queue}
                    label={`Type the source queue's name, "${data.source.queue}", to confirm`}
                    confirmLabel={confirmLabel}
                    color={data.mode === 'MOVE' ? 'red' : 'pine'}
                    loading={execute.isPending}
                    disabled={unacked > 0 || execute.isPending}
                    onConfirm={start}
                  />
                ) : (
                  <Group>
                    <Button size="xs" loading={execute.isPending} disabled={unacked > 0} onClick={start}>
                      {confirmLabel}
                    </Button>
                  </Group>
                )}
              </Stack>
            )}

            <Group>
              <Button
                size="xs"
                variant="subtle"
                onClick={() => {
                  preview.reset();
                  execute.reset();
                }}
              >
                Change the destination
              </Button>
            </Group>
          </Stack>
        ) : null}
      </Stack>
    </Modal>
  );
}
