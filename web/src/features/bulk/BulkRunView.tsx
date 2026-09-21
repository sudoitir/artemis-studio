import { useState } from 'react';
import { Alert, Anchor, Button, Group, Modal, Progress, Skeleton, Stack, Text, Title } from '@mantine/core';
import { Link, useParams } from '@tanstack/react-router';

import { useCan } from '../../kernel/auth/useCan.ts';
import { absoluteLabel } from '../../kernel/time/time.ts';
import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { gateFor } from '../../ui/capabilityGate.ts';
import { NodeOutcomeSummary } from '../../ui/NodeOutcomeSummary.tsx';
import { VirtualTable, type GridColumn } from '../../ui/VirtualTable.tsx';
import { useBulkRun, useBulkStop, type BulkItemView, type BulkRunView as Run, type LifecycleOutcomeView } from './api.ts';
import { itemStatus, OPERATIONS, plural, runStatus } from './words.ts';

/** An acted-on queue's per-node result, in the single-queue command's shape. */
function outcomeOf(item: BulkItemView, run: Run): LifecycleOutcomeView | null {
  if (!item.outcome) return null;
  const landed = item.outcome.some((n) => n.status === 'APPLIED' || n.status === 'ALREADY');
  return {
    dryRun: false,
    cap: run.cap,
    overCap: false,
    partial: landed && item.outcome.some((n) => n.status === 'FAILED'),
    totalAffected: item.affected ?? 0,
    nodes: item.outcome.map((n) => ({
      nodeId: n.nodeId ?? '',
      nodeName: n.nodeName ?? '',
      status: n.status ?? 'FAILED',
      affected: n.affected ?? null,
      error: n.error ?? null,
    })),
  };
}

/** The counts in words, so the outcome does not depend on reading a bar. */
function counts(run: Run): string {
  const done = run.succeeded + run.failed + run.skipped;
  return `${run.succeeded.toLocaleString()} succeeded, ${run.failed.toLocaleString()} failed, ${run.skipped.toLocaleString()} skipped, ${done.toLocaleString()} of ${plural(run.total, 'queue')} settled`;
}

const toneColor = (tone: 'warning' | 'danger' | undefined) =>
  tone === 'danger' ? 'var(--as-danger)' : tone === 'warning' ? 'var(--as-warning)' : undefined;

const TERMINAL = new Set<Run['status']>(['SUCCEEDED', 'PARTIAL', 'FAILED', 'STOPPED', 'INTERRUPTED']);

/**
 * One bulk run at its own address: its status in words, its progress, and each queue's outcome,
 * expandable to the per-node detail. Kept live by the `bulk` stream topic, so a reload picks the run
 * up where it is.
 */
export function BulkRunView() {
  useDisplayZone();
  const { clusterId, runId } = useParams({ strict: false }) as { clusterId: string; runId: string };
  const query = useBulkRun(clusterId, runId);
  const stop = useBulkStop(clusterId, runId);
  const { can, loading } = useCan();
  const [open, setOpen] = useState<string | null>(null);
  const [stopOpen, setStopOpen] = useState(false);

  if (query.isError) {
    return (
      <Alert color="red" variant="light" title={query.error.title}>
        {query.error.message}
      </Alert>
    );
  }
  if (!query.data) {
    return <Skeleton height={160} />;
  }

  const { run, items } = query.data;
  const op = OPERATIONS[run.operation];
  const status = runStatus(run.status);
  const done = run.succeeded + run.failed + run.skipped;
  const opened = items.find((i) => i.queueName === open) ?? null;
  const openedOutcome = opened ? outcomeOf(opened, run) : null;
  // The server re-checks; this only explains. Offered while grants load.
  const stopGate = gateFor(can(op.permission, clusterId), op.permissionLabel, undefined, loading);

  const columns: GridColumn<BulkItemView>[] = [
    {
      id: 'queue',
      header: 'Queue',
      accessor: (i) => i.queueName,
      // A real button, so the per-node detail is reachable from the keyboard.
      cell: (i) => (
        <Anchor
          component="button"
          size="sm"
          aria-label={`Show node detail for ${i.queueName}`}
          onClick={() => setOpen(i.queueName)}
        >
          {i.queueName}
        </Anchor>
      ),
    },
    {
      id: 'status',
      header: 'Outcome',
      accessor: (i) => itemStatus(i.status).text,
      cell: (i) => {
        const s = itemStatus(i.status);
        return (
          <Text size="sm" c={toneColor(s.tone)}>
            {s.text}
          </Text>
        );
      },
      width: 200,
    },
    ...(op.destructive
      ? [
          {
            id: 'affected',
            header: 'Messages',
            accessor: (i: BulkItemView) => (i.affected == null ? 'unknown' : i.affected.toLocaleString()),
            numeric: true,
            width: 110,
          },
        ]
      : []),
    { id: 'note', header: 'Reason', accessor: (i) => i.error ?? i.warning ?? '' },
  ];

  return (
    <Stack gap="sm">
      <Group justify="space-between" align="flex-end">
        <Stack gap={2}>
          <Title order={3}>
            {op.verb} {plural(run.total, 'queue')}
          </Title>
          <Text size="xs" c="dimmed">
            Started by {run.username}
            {run.startedAt ? ` at ${absoluteLabel(run.startedAt)}` : ''}
            {run.continueOnFailure ? ', continuing past failures' : ', stopping at the first failure'}
            {run.overrideCap ? ', with the safety cap overridden' : ''}
          </Text>
        </Stack>
        <Group gap="xs">
          {run.auditEventId != null ? (
            <Anchor
              component={Link}
              to={`/clusters/${clusterId}/audit`}
              // The untyped router cannot type another feature's search; `QueueHistoryPanels` does the same.
              search={{ parentId: run.auditEventId } as never}
              size="sm"
            >
              Audit trail for this run
            </Anchor>
          ) : null}
          {run.status === 'RUNNING' ? (
            <CapabilityGate verdict={stopGate} what="stopping this run">
              <Button
                size="xs"
                variant="light"
                color="red"
                disabled={stopGate.kind === 'blocked'}
                onClick={() => setStopOpen(true)}
              >
                Stop run
              </Button>
            </CapabilityGate>
          ) : null}
        </Group>
      </Group>

      {/* Announced as it changes; the terminal outcome is what a screen-reader user waits for. */}
      <div role="status" aria-live="polite" aria-label="Run outcome">
        <Text
          size="sm"
          fw={600}
          c={toneColor(status.tone)}
        >
          {TERMINAL.has(run.status) ? `This run finished. ${status.text}.` : `${status.text}.`}
        </Text>
        <Text size="sm" style={{ fontVariantNumeric: 'tabular-nums' }}>
          {counts(run)}.
        </Text>
        {run.error ? <Text size="sm">{run.error}</Text> : null}
      </div>

      {/* The theme honours reduced motion, so the bar's transition drops out for those who ask. */}
      <Progress
        aria-label="Queues settled"
        value={run.total === 0 ? 0 : (done / run.total) * 100}
        color={toneColor(status.tone)}
      />

      {stop.isError ? (
        <Alert color="red" variant="light" title={stop.error.title} role="alert">
          {stop.error.message}
        </Alert>
      ) : null}

      <VirtualTable columns={columns} data={items} rowKey={(i) => i.queueName} />

      {opened ? (
        <Stack gap="xs">
          <Group justify="space-between">
            <Title order={5}>{opened.queueName}</Title>
            <Button size="xs" variant="subtle" onClick={() => setOpen(null)}>
              Hide node detail
            </Button>
          </Group>
          {openedOutcome ? (
            <NodeOutcomeSummary outcome={openedOutcome} destructive={op.destructive} />
          ) : (
            <Text size="sm">
              {opened.status === 'REFUSED'
                ? `Refused at preview: ${opened.error ?? 'no reason recorded'}. Nothing was sent to the broker.`
                : 'This queue has not been acted on, so there is no per-node result.'}
            </Text>
          )}
        </Stack>
      ) : null}

      <Modal opened={stopOpen} onClose={() => setStopOpen(false)} title="Stop this run?">
        <Stack gap="sm">
          <Text size="sm">
            The queue being acted on now finishes; every queue after it is cancelled and left as it is. What has
            already been done is not undone.
          </Text>
          <Group justify="flex-end">
            <Button
              size="xs"
              color="red"
              loading={stop.isPending}
              onClick={() => stop.mutate(undefined, { onSettled: () => setStopOpen(false) })}
            >
              Stop run
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}
