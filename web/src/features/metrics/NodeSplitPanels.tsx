import { useMemo } from 'react';
import { Alert, Skeleton, Stack, Text } from '@mantine/core';
import { CompositeChart } from '@mantine/charts';

import { useServerNow } from '../../kernel/time/time.ts';
import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { rangeSpec, type MetricRange } from '../../kernel/time/ranges.ts';
import { useMetrics, type MetricNodeSeries, type MetricSeriesResponse } from './api.ts';
import {
  formatCount,
  formatRate,
  gridProps,
  labelFormatter,
  latest,
  mergeByTimestamp,
  timeAxisProps,
  yAxisProps,
} from './axis.ts';
import classes from './NodeSplit.module.css';

const METRICS = ['messageCount', 'messagesAdded', 'messagesAcked'];
const SMALL_HEIGHT = 120;

const series = (node: MetricNodeSeries, metric: string) => node.series.find((s) => s.metric === metric);
const peak = (nodes: MetricNodeSeries[], metrics: string[]) =>
  Math.max(
    1,
    ...nodes.flatMap((n) => metrics.flatMap((m) => (series(n, m)?.points ?? []).map((p) => p.value ?? 0))),
  );

/**
 * A queue's history as small multiples, one per broker node, on one shared scale (ADR-0110): a
 * node's chart is comparable with its neighbour's at a glance, and no node is told apart by colour.
 * Each node leads with its latest figures in words, so the charts are never the only carrier.
 */
export function NodeSplitCharts({
  response,
  range,
  from,
  to,
  syncId,
}: {
  response: MetricSeriesResponse;
  range: MetricRange;
  from: number;
  to: number;
  syncId: string;
}) {
  useDisplayZone();
  const nodes = response.byNode ?? [];
  const depthMax = peak(nodes, ['messageCount']);
  const rateMax = peak(nodes, ['messagesAdded', 'messagesAcked']);
  const common = {
    h: SMALL_HEIGHT,
    dataKey: 'ts',
    connectNulls: false,
    withDots: false,
    composedChartProps: { syncId },
    xAxisProps: timeAxisProps(range, from, to),
    gridProps: gridProps(),
    gridAxis: 'y' as const,
    tooltipProps: { labelFormatter: (label: unknown) => labelFormatter(range)(Number(label)) },
  };

  if (nodes.length === 0) {
    return (
      <Text size="sm" c="dimmed">
        No node has served this queue in this window.
      </Text>
    );
  }

  return (
    <Stack gap="xs">
      {response.truncated ? (
        <Text size="xs" c="dimmed">
          Adjusted to keep the split bounded: each bucket is {response.step}, and at most 16 nodes are shown.
        </Text>
      ) : null}
      <div className={classes.grid}>
        {nodes.map((node) => {
          const depth = latest(series(node, 'messageCount'));
          const added = latest(series(node, 'messagesAdded'));
          const acked = latest(series(node, 'messagesAcked'));
          return (
            <section key={node.nodeId} className={classes.node} aria-label={`History on ${node.nodeName}`}>
              <Text size="sm" fw={600}>
                {node.nodeName}
              </Text>
              {!node.sampled ? (
                <Text size="sm" c="dimmed">
                  Not sampled in this window, which is not the same as empty.
                </Text>
              ) : (
                <>
                  <Text size="xs" c="dimmed" className={classes.figures}>
                    Latest: {depth === null ? 'no depth' : `${formatCount(depth)} waiting`} · in{' '}
                    {added === null ? 'unknown' : `${formatRate(added)} msg/s`} · out{' '}
                    {acked === null ? 'unknown' : `${formatRate(acked)} msg/s`}
                  </Text>
                  <CompositeChart
                    {...common}
                    data={mergeByTimestamp([{ name: 'depth', series: series(node, 'messageCount') }])}
                    valueFormatter={formatCount}
                    yAxisProps={{ ...yAxisProps(), domain: [0, depthMax] }}
                    series={[{ name: 'depth', label: 'Depth (avg)', color: 'var(--as-chart-1)', type: 'line' }]}
                  />
                  <CompositeChart
                    {...common}
                    data={mergeByTimestamp([
                      { name: 'added', series: series(node, 'messagesAdded') },
                      { name: 'acked', series: series(node, 'messagesAcked') },
                    ])}
                    withLegend
                    valueFormatter={(v) => `${formatRate(v)} msg/s`}
                    yAxisProps={{ ...yAxisProps(), domain: [0, rateMax] }}
                    series={[
                      { name: 'added', label: 'Added', color: 'var(--as-chart-1)', type: 'line' },
                      { name: 'acked', label: 'Acked', color: 'var(--as-chart-2)', type: 'line' },
                    ]}
                  />
                </>
              )}
            </section>
          );
        })}
      </div>
    </Stack>
  );
}

/** A queue's per-node history in the flow view's pane (`flow.selection.panels`). */
export function NodeSplitPanels({
  clusterId,
  queueName,
  range,
}: {
  clusterId: string;
  queueName: string;
  range: MetricRange;
}) {
  const spec = rangeSpec(range);
  const tick = useServerNow(spec.stepMs);
  const { from, to } = useMemo(() => {
    const end = Math.floor(tick / spec.stepMs) * spec.stepMs;
    return { from: end - spec.windowMs, to: end };
  }, [tick, spec.stepMs, spec.windowMs]);
  const metrics = useMetrics(
    clusterId,
    {
      metrics: METRICS,
      subjectType: 'QUEUE',
      subject: queueName,
      from: new Date(from).toISOString(),
      to: new Date(to).toISOString(),
      step: spec.step,
      splitBy: 'NODE',
    },
    Math.max(15_000, spec.stepMs),
  );

  if (metrics.isError) {
    return (
      <Alert color="red" variant="light" title={metrics.error.title ?? 'History could not be read'}>
        {metrics.error.message} — this window could not be read, which is not the same as there being nothing in it.
      </Alert>
    );
  }
  if (!metrics.data) return <Skeleton height={SMALL_HEIGHT * 2} aria-label="Loading history per node" />;
  return (
    <NodeSplitCharts
      response={metrics.data}
      range={range}
      from={from}
      to={to}
      syncId={`flow-split-${clusterId}-${queueName}`}
    />
  );
}
