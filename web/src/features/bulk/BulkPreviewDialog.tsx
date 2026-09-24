import { useState } from 'react';
import { Alert, Button, Checkbox, Group, Modal, Stack, Switch, Text, Title } from '@mantine/core';
import { useNavigate } from '@tanstack/react-router';

import type { QueueSelection } from '../../kernel/slots.ts';
import { ConfirmByTyping } from '../../ui/ConfirmByTyping.tsx';
import { VirtualTable, type GridColumn } from '../../ui/VirtualTable.tsx';
import { useBulkExecute, useBulkPreview, type BulkItemView, type BulkOperation, type BulkRunDetailView } from './api.ts';
import { OPERATIONS, plural } from './words.ts';

const hasProblem = (i: BulkItemView) => i.status === 'REFUSED' || Boolean(i.warning);

function columns(destructive: boolean): GridColumn<BulkItemView>[] {
  return [
    { id: 'queue', header: 'Queue', accessor: (i) => i.queueName },
    ...(destructive
      ? [
          {
            id: 'messages',
            header: 'Messages',
            // An unknown figure is stated, never shown as zero.
            accessor: (i: BulkItemView) => (i.affected == null ? 'unknown' : i.affected.toLocaleString()),
            numeric: true,
            width: 110,
          },
        ]
      : []),
    { id: 'nodes', header: 'Nodes', accessor: (i) => i.nodes.length, numeric: true, width: 80 },
    {
      id: 'plan',
      header: 'Plan',
      accessor: (i) => (i.status === 'REFUSED' ? 'refused' : 'will act'),
      width: 100,
    },
    { id: 'note', header: 'Reason or warning', accessor: (i) => i.error ?? i.warning ?? '' },
  ];
}

/** What the run would do, as one sentence an operator can check before arming it. */
function blastRadius(operation: BulkOperation, preview: BulkRunDetailView): string {
  const { run, items } = preview;
  const op = OPERATIONS[operation];
  const acting = items.filter((i) => i.status !== 'REFUSED');
  const nodes = new Set(acting.flatMap((i) => i.nodes.map((n) => n.nodeId))).size;
  const scope = `${op.verb} ${plural(acting.length, 'queue')} on ${plural(nodes, 'node')}`;
  if (!op.destructive) return `${scope}.`;
  if (run.estimate == null || (acting.length > 0 && acting.every((i) => i.affected == null))) {
    return `${scope}. How many messages that destroys is unavailable. This cannot be undone.`;
  }
  const messages = plural(run.estimate, 'message');
  return run.estimateComplete
    ? `${scope}, destroying ${messages}. This cannot be undone.`
    : `${scope}, destroying at least ${messages}. This cannot be undone.`;
}

/**
 * Preview → confirm → execute for one bulk operation (ADR-0093). The preview freezes the queue set
 * and states the blast radius; only that frozen plan can be confirmed, and a destroy is armed by
 * typing the action and the count. Once the run is accepted the operator is taken to it.
 */
export function BulkPreviewDialog({
  clusterId,
  operation,
  selection,
  opened,
  onClose,
  onStarted,
}: {
  clusterId: string;
  operation: BulkOperation;
  selection: QueueSelection;
  opened: boolean;
  onClose: () => void;
  onStarted: () => void;
}) {
  const op = OPERATIONS[operation];
  const navigate = useNavigate();
  const preview = useBulkPreview(clusterId);
  const execute = useBulkExecute(clusterId);
  const [onlyProblems, setOnlyProblems] = useState(false);
  const [continueOnFailure, setContinueOnFailure] = useState(false);
  const [override, setOverride] = useState(false);

  const takePreview = () => {
    execute.reset();
    preview.mutate({
      operation,
      names: selection.kind === 'names' ? selection.names : null,
      q: selection.kind === 'filter' ? selection.q : null,
      // A bulk delete refuses a queue with consumers; closing them is not offered in bulk yet.
      disconnectConsumers: false,
    });
  };

  const close = () => {
    preview.reset();
    execute.reset();
    setOnlyProblems(false);
    setContinueOnFailure(false);
    setOverride(false);
    onClose();
  };

  const data = preview.data;
  const acting = data?.items.filter((i) => i.status !== 'REFUSED') ?? [];
  const refused = (data?.items.length ?? 0) - acting.length;
  const unknown = op.destructive ? acting.filter((i) => i.affected == null).length : 0;
  const overCap = data?.run.overCap ?? false;
  const shown = onlyProblems ? (data?.items.filter(hasProblem) ?? []) : (data?.items ?? []);
  const confirmLabel = `${op.verb} ${plural(acting.length, 'queue')}`;

  const start = () => {
    if (!data) return;
    execute.mutate(
      { runId: data.run.id, body: { planHash: data.run.planHash, override: overCap && override, continueOnFailure } },
      {
        onSuccess: (run) => {
          close();
          navigate({ to: `/clusters/${clusterId}/bulk/${run.id}` });
          onStarted();
        },
      },
    );
  };

  return (
    <Modal
      opened={opened}
      onClose={close}
      onEnterTransitionEnd={takePreview}
      title={`${op.verb} the selected queues`}
      size="xl"
    >
      <Stack gap="md">
        <div aria-live="polite">
          {preview.isPending ? (
            <Text size="sm" c="dimmed">
              Reading what these queues hold…
            </Text>
          ) : null}
          {preview.isError ? (
            <Alert color="red" variant="light" title={preview.error.title} role="alert">
              <Stack gap="xs" align="flex-start">
                <Text size="sm">{preview.error.message}</Text>
                <Button size="xs" variant="light" onClick={takePreview}>
                  Preview again
                </Button>
              </Stack>
            </Alert>
          ) : null}
          {data ? (
            <Stack gap={4}>
              <Text size="sm" fw={600}>
                {blastRadius(operation, data)}
              </Text>
              {unknown > 0 ? (
                <Text size="sm">
                  {plural(unknown, 'queue')} {unknown === 1 ? 'has a figure' : 'have figures'} Studio does not
                  know, because a node has not answered recently; the total is a floor.
                </Text>
              ) : null}
              {refused > 0 ? (
                <Text size="sm">
                  {plural(refused, 'queue')} {refused === 1 ? 'is' : 'are'} refused and will not be touched. The
                  reason is beside each one below.
                </Text>
              ) : null}
            </Stack>
          ) : null}
        </div>

        {data && overCap ? (
          <Alert color="yellow" variant="light" title="Over the safety cap">
            <Stack gap="xs">
              <Text size="sm">
                This run would destroy {data.run.estimate?.toLocaleString()} messages, over the cap of{' '}
                {data.run.cap.toLocaleString()}. It can run only with the cap overridden for this run, and the
                override is recorded in the audit log.
              </Text>
              <Checkbox
                label="Override the safety cap for this run"
                checked={override}
                onChange={(e) => setOverride(e.currentTarget.checked)}
              />
            </Stack>
          </Alert>
        ) : null}

        {data && acting.length > 0 ? (
          <>
            <Switch
              label="Continue past failures"
              description={
                continueOnFailure
                  ? 'Every queue is attempted, whatever happens to the ones before it.'
                  : 'The run stops at the first queue that fails, and the queues after it are skipped.'
              }
              checked={continueOnFailure}
              onChange={(e) => setContinueOnFailure(e.currentTarget.checked)}
            />

            {execute.isError ? (
              <Alert color="red" variant="light" title={execute.error.title} role="alert">
                <Stack gap="xs" align="flex-start">
                  <Text size="sm">{execute.error.message}</Text>
                  <Text size="sm">Nothing was run. Preview again to confirm the queues as they are now.</Text>
                  <Button size="xs" variant="light" onClick={takePreview}>
                    Preview again
                  </Button>
                </Stack>
              </Alert>
            ) : null}

            {op.destructive ? (
              <ConfirmByTyping
                token={`${op.verb.toLowerCase()} ${acting.length} ${acting.length === 1 ? 'queue' : 'queues'}`}
                confirmLabel={confirmLabel}
                loading={execute.isPending}
                disabled={(overCap && !override) || execute.isPending}
                onConfirm={start}
              />
            ) : (
              <Group>
                <Button size="xs" loading={execute.isPending} onClick={start}>
                  {confirmLabel}
                </Button>
              </Group>
            )}
          </>
        ) : null}

        {data && acting.length === 0 ? (
          <Text size="sm">Every selected queue is refused, so there is nothing to run. Close this and change the selection.</Text>
        ) : null}

        {data ? (
          <Stack gap="xs">
            <Group justify="space-between">
              <Title order={5}>The queues in this run</Title>
              <Switch
                size="xs"
                label="Only queues with a refusal or warning"
                checked={onlyProblems}
                onChange={(e) => setOnlyProblems(e.currentTarget.checked)}
              />
            </Group>
            <VirtualTable
              label="Queues in this run"
              columns={columns(op.destructive)}
              data={shown}
              rowKey={(i) => i.queueName}
              emptyLabel={
                <Text size="sm">
                  {onlyProblems ? 'No queue has a refusal or a warning.' : 'No queue matched the selection.'}
                </Text>
              }
            />
          </Stack>
        ) : null}
      </Stack>
    </Modal>
  );
}
