import { CloseButton, Group, SegmentedControl, Stack, Text, Title } from '@mantine/core';

import { useSlot } from '../../kernel/slots.ts';
import { METRIC_RANGES, type MetricRange } from '../../kernel/time/ranges.ts';
import { VirtualTable, type GridColumn } from '../../ui/VirtualTable.tsx';
import type { FlowGraphView, FlowNodeShare, FlowNodeView } from './api.ts';
import { FAULT_LABELS, formatCount, formatRate } from './flowFormat.ts';
import { imbalance } from './imbalance.ts';
import classes from './FlowView.module.css';

const KIND_WORD: Record<string, string> = {
  PRODUCER: 'Producing client',
  CONSUMER: 'Consuming client',
  ADDRESS: 'Address',
  QUEUE: 'Queue',
  REMOTE: 'Remote',
};

const UNKNOWN = 'unknown';
const MEASURING = 'measuring…';
const count = (n: number | null | undefined) => (n === null || n === undefined ? UNKNOWN : formatCount(n));
// Rates are messages per second, named in the column headers ("In/s"), so the figures fit the pane.
const rate = (n: number | null | undefined) => (n === null || n === undefined ? MEASURING : formatRate(n));

/** One row per broker node; `stale` is a node that did not answer, whose figures are unknown, not zero. */
interface NodeRow {
  nodeId: string;
  node: string;
  messageCount?: number | null;
  consumerCount?: number | null;
  inRate?: number | null;
  outRate?: number | null;
  stale: boolean;
}

function figure(value: (r: NodeRow) => string, r: NodeRow) {
  return <Text size="sm" className={r.stale ? classes.stale : classes.figure}>{r.stale ? UNKNOWN : value(r)}</Text>;
}

type Columns = 'resource' | 'producer' | 'consumer';

function nodeColumns(shape: Columns): GridColumn<NodeRow>[] {
  // The node's state rides in its own cell: a column for it would not fit beside the graph.
  const columns: GridColumn<NodeRow>[] = [
    {
      id: 'node',
      header: 'Node',
      accessor: (r) => (r.stale ? `${r.node} (did not answer)` : r.node),
      cell: (r) => (
        <Text size="sm" truncate title={r.node}>
          {r.node}
          {r.stale ? (
            <Text span size="xs" className={classes.stale}>
              {' '}
              did not answer
            </Text>
          ) : null}
        </Text>
      ),
    },
  ];
  const inRate: GridColumn<NodeRow> = {
    id: 'in',
    header: shape === 'producer' ? 'Sends/s' : 'In/s',
    numeric: true,
    width: shape === 'producer' ? 100 : 72,
    accessor: (r) => rate(r.inRate),
    cell: (r) => figure((x) => rate(x.inRate), r),
  };
  const outRate: GridColumn<NodeRow> = {
    id: 'out',
    header: shape === 'consumer' ? 'Receives/s' : 'Out/s',
    numeric: true,
    width: shape === 'consumer' ? 116 : 76,
    accessor: (r) => rate(r.outRate),
    cell: (r) => figure((x) => rate(x.outRate), r),
  };
  if (shape === 'producer') return [...columns, inRate];
  if (shape === 'consumer') return [...columns, outRate];
  return [
    ...columns,
    { id: 'backlog', header: 'Backlog', numeric: true, width: 84, accessor: (r) => count(r.messageCount), cell: (r) => figure((x) => count(x.messageCount), r) },
    { id: 'consumers', header: 'Consumers', numeric: true, width: 112, accessor: (r) => count(r.consumerCount), cell: (r) => figure((x) => count(x.consumerCount), r) },
    inRate,
    outRate,
  ];
}

function NodeTable({ label, rows, shape }: { label: string; rows: NodeRow[]; shape: Columns }) {
  return (
    <VirtualTable
      label={label}
      compact
      columns={nodeColumns(shape)}
      data={rows}
      rowKey={(r) => r.nodeId}
      emptyLabel={<Text size="sm">No broker node reported this.</Text>}
    />
  );
}

/** A client's rates per node, from its edges: what it produces is messages in, what it consumes is out. */
function clientRows(graph: FlowGraphView, node: FlowNodeView): NodeRow[] {
  const rows = new Map<string, NodeRow>();
  for (const edge of graph.edges ?? []) {
    const produced = edge.source === node.id;
    if (!produced && edge.target !== node.id) continue;
    for (const r of edge.byNode ?? []) {
      const row = rows.get(r.nodeId) ?? { nodeId: r.nodeId, node: r.node, stale: false };
      row.stale ||= r.stale;
      const field = produced ? 'inRate' : 'outRate';
      if (r.rate !== null && r.rate !== undefined) row[field] = (row[field] ?? 0) + r.rate;
      rows.set(r.nodeId, row);
    }
  }
  return [...rows.values()].sort((a, b) => a.node.localeCompare(b.node));
}

/**
 * The Split layout's monitoring pane (flow-visualization spec, ADR-0110): what the selection is
 * now, per broker node, with any imbalance stated in words; for a queue, its history per node from
 * whichever feature contributes `flow.selection.panels`. With nothing selected, each broker node's
 * totals. It reads only what the flow graph already carries, and never changes the broker.
 */
export function FlowMonitorPane({
  clusterId,
  graph,
  nodeId,
  range,
  breakdownPending,
  onRangeChange,
  onClear,
}: {
  clusterId: string;
  graph: FlowGraphView;
  nodeId: string | null;
  range: MetricRange;
  /** The graph on screen is the one from before the breakdown was asked for. */
  breakdownPending: boolean;
  onRangeChange: (range: MetricRange) => void;
  onClear: () => void;
}) {
  const panels = useSlot('flow.selection.panels');
  const node = nodeId ? (graph.nodes ?? []).find((n) => n.id === nodeId) : undefined;

  if (!node) {
    const rows: NodeRow[] = (graph.brokerNodes ?? []).map((b) => ({
      nodeId: b.nodeId ?? b.name ?? '',
      node: b.name ?? b.nodeId ?? '',
      messageCount: b.backlog,
      consumerCount: b.consumers,
      inRate: b.inRate,
      outRate: b.outRate,
      stale: b.state === 'UNREACHABLE' || b.state === 'FAILED',
    }));
    return (
      <section className={classes.pane} aria-label="Broker nodes">
        <Stack gap="sm">
          <Title order={4}>Broker nodes</Title>
          <Text size="sm" c="dimmed">
            {nodeId
              ? 'The selection is not in the shown paths any more. '
              : ''}
            Select a client, address or queue to break it down per node. These are each node's totals now.
          </Text>
          <NodeTable label="Totals per broker node" rows={rows} shape="resource" />
        </Stack>
      </section>
    );
  }

  const client = node.kind === 'PRODUCER' || node.kind === 'CONSUMER';
  const shares: FlowNodeShare[] = node.byNode ?? [];
  const rows: NodeRow[] = client ? clientRows(graph, node) : shares;
  const statements = client ? [] : imbalance(shares);
  const faults = (node.faults ?? []).map((f) => FAULT_LABELS[f] ?? f.toLowerCase());
  const kind = KIND_WORD[node.kind ?? ''] ?? 'Node';

  return (
    <section className={classes.pane} aria-label={`${kind} ${node.label} per node`}>
      <Stack gap="md">
        <Group justify="space-between" align="flex-start" wrap="nowrap">
          <div className={classes.inspectorTitle}>
            <Text size="xs" c="dimmed">
              {kind}
            </Text>
            <Title order={4} className={classes.breakAnywhere}>
              {node.label}
            </Title>
          </div>
          <CloseButton aria-label="Clear selection" onClick={onClear} />
        </Group>

        {faults.length ? (
          <Text size="sm" fw={600} className={classes.alarm}>
            {faults.join(', ')}
          </Text>
        ) : null}

        {breakdownPending ? (
          <Text size="sm" c="dimmed" role="status">
            Breaking this down per node…
          </Text>
        ) : rows.length === 0 ? (
          <Text size="sm" c="dimmed">
            {client
              ? 'No per-node rate is measured for this client yet.'
              : 'No broker node reports this on its own, so there is no per-node breakdown.'}
          </Text>
        ) : (
          <>
            {statements.length ? (
              <ul className={classes.statements} aria-label="Balance across nodes">
                {statements.map((s) => (
                  <li key={s.text} data-kind={s.kind}>
                    <Text size="sm" fw={s.kind === 'balanced' || s.kind === 'unknown' ? undefined : 600}>
                      {s.text}
                    </Text>
                  </li>
                ))}
              </ul>
            ) : null}
            <NodeTable
              label={`${node.label} per node`}
              rows={rows}
              shape={node.kind === 'PRODUCER' ? 'producer' : node.kind === 'CONSUMER' ? 'consumer' : 'resource'}
            />
          </>
        )}

        {client ? (
          <Text size="sm" c="dimmed">
            Client history is not kept, so there are no trends for a client.
          </Text>
        ) : node.kind === 'QUEUE' && panels.length > 0 ? (
          <Stack gap="xs">
            <Group justify="space-between" align="center" wrap="wrap" gap="xs">
              <Title order={5}>Over time</Title>
              <SegmentedControl
                size="xs"
                aria-label="History range"
                data={METRIC_RANGES.map((r) => ({ value: r, label: r }))}
                value={range}
                onChange={(value) => onRangeChange(value as MetricRange)}
              />
            </Group>
            {panels.map(({ id, Component }) => (
              <Component key={id} clusterId={clusterId} queueName={node.label ?? ''} range={range} />
            ))}
          </Stack>
        ) : null}
      </Stack>
    </section>
  );
}
