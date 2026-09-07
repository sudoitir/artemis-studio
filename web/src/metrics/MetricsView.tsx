import { useMemo } from 'react';
import { Alert, Anchor, Badge, Card, Group, Spoiler, Stack, Text, Title } from '@mantine/core';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';

import { useMetrics, type MetricSeries } from '../api/client.ts';
import { useServerNow } from '../app/time.ts';
import { rangeSpec, type MetricRange } from './ranges.ts';
import { RangePicker } from './RangePicker.tsx';
import { ChartPanel } from './ChartPanel.tsx';
import { DepthChart } from './DepthChart.tsx';
import { ThroughputChart } from './ThroughputChart.tsx';
import { ConsumersChart } from './ConsumersChart.tsx';
import { MetricsTable } from './MetricsTable.tsx';
import { StatRow, type Stat } from './StatRow.tsx';
import { earliest, formatCount, formatExact, formatRate, latest } from './axis.ts';
import { LatencyPanel } from '../rr/LatencyPanel.tsx';

const METRICS = ['messageCount', 'consumerCount', 'messagesAdded', 'messagesAcked'];

/**
 * Cluster-wide (or queue-scoped) historical metrics: depth, throughput, consumers,
 * and request-reply latency, sharing one crosshair.
 *
 * A relative range advances as time passes, quantized to the bucket width, so the
 * right-hand edge is still the present an hour after the page was opened — and so
 * the query key changes once per bucket rather than once per second (ADR-0055).
 * An absolute range never advances and never polls: it is a link to a moment.
 */
export function MetricsView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const navigate = useNavigate();
  const search = useSearch({ strict: false }) as {
    range?: MetricRange;
    from?: string;
    to?: string;
    subject?: string;
  };

  const live = !search.from && !search.to;
  const range = search.range ?? '1h';
  const spec = rangeSpec(range);
  const subject = search.subject;

  // Ticks at the bucket width, not at a second: the window can only move when a
  // new bucket exists, and a key that changed every second would mint a cache
  // entry a second.
  const tick = useServerNow(spec.stepMs);

  const { from, to } = useMemo(() => {
    if (!live) return { from: search.from!, to: search.to! };
    const end = Math.floor(tick / spec.stepMs) * spec.stepMs;
    return {
      from: new Date(end - spec.windowMs).toISOString(),
      to: new Date(end).toISOString(),
    };
  }, [live, tick, spec.stepMs, spec.windowMs, search.from, search.to]);

  const metrics = useMetrics(
    clusterId,
    {
      metrics: METRICS,
      subjectType: subject ? 'QUEUE' : 'CLUSTER',
      subject,
      from,
      to,
      step: live ? spec.step : undefined,
    },
    live ? Math.max(15_000, spec.stepMs) : false,
  );

  const byName = (name: string): MetricSeries | undefined =>
    metrics.data?.series.find((s) => s.metric === name);
  const depth = byName('messageCount');
  const added = byName('messagesAdded');
  const acked = byName('messagesAcked');
  const consumers = byName('consumerCount');

  const syncId = `cluster-metrics-${clusterId}`;
  // The axis domain is the *requested* window, so a series that stops halfway
  // renders as a half-empty chart rather than one silently rescaled to fit.
  const fromMs = Date.parse(from);
  const toMs = Date.parse(to);

  // `isPending` alone is wrong here: `placeholderData` keeps the previous window's
  // data while a new one loads, and a skeleton over data an operator is reading is
  // worse than a slightly stale chart.
  const isPending = metrics.isPending && !metrics.data;
  const error = metrics.isError ? metrics.error : null;
  const empty = (s: MetricSeries | undefined) => !error && !isPending && !s?.points?.length;
  const scope = subject ? `queue ${subject}` : 'this cluster';

  const stats: Stat[] = [
    { label: 'Depth', unit: 'messages', value: latest(depth), since: earliest(depth), format: formatCount },
    { label: 'Added', unit: 'msg/s', value: latest(added), since: earliest(added), format: formatRate },
    { label: 'Acked', unit: 'msg/s', value: latest(acked), since: earliest(acked), format: formatRate },
    {
      label: 'Consumers',
      unit: 'connected',
      value: latest(consumers),
      since: earliest(consumers),
      format: formatExact,
    },
  ];

  return (
    <Stack gap="lg">
      <Group justify="space-between" align="center">
        <Group gap="sm" align="center">
          <Title order={3}>Metrics</Title>
          {subject ? (
            <Badge variant="light" tt="none">
              {subject}
            </Badge>
          ) : null}
        </Group>
        <RangePicker />
      </Group>

      {subject ? (
        <Alert color="gray" variant="light" title="Scoped to one queue">
          These series cover <strong>{subject}</strong> only.{' '}
          <Anchor
            component="button"
            type="button"
            onClick={() =>
              navigate({
                to: '.',
                search: (prev: Record<string, unknown>) => ({ ...prev, subject: undefined }),
              })
            }
          >
            Show the whole cluster
          </Anchor>
          .
        </Alert>
      ) : null}

      {metrics.data?.truncated ? (
        <Alert color="gray" variant="light" title="Window adjusted">
          The requested resolution or range was wider than this cluster's retention or
          sampling cadence allows; the charts below show the {metrics.data.step} bucket
          Studio actually used.
        </Alert>
      ) : null}

      <StatRow stats={stats} />

      <ChartPanel
        title="Depth"
        unit="messages"
        isPending={isPending}
        error={error}
        isEmpty={empty(depth)}
        emptyLabel={`No depth samples for ${scope} in this window. Depth is recorded on the queue sweep, so a cluster registered within the last few minutes has none yet.`}
      >
        <DepthChart series={depth} range={range} from={fromMs} to={toMs} syncId={syncId} />
      </ChartPanel>

      <ChartPanel
        title="Throughput"
        unit="messages per second"
        isPending={isPending}
        error={error}
        isEmpty={empty(added) && empty(acked)}
        emptyLabel={`No throughput samples for ${scope} in this window. A rate needs two samples in the window to exist at all, so a very narrow range on a newly registered cluster is empty rather than zero.`}
      >
        <ThroughputChart
          added={added}
          acked={acked}
          range={range}
          from={fromMs}
          to={toMs}
          syncId={syncId}
        />
      </ChartPanel>

      <ChartPanel
        title="Consumers"
        unit="connected consumers"
        isPending={isPending}
        error={error}
        isEmpty={empty(consumers)}
        emptyLabel={`No consumer samples for ${scope} in this window.`}
      >
        <ConsumersChart series={consumers} range={range} from={fromMs} to={toMs} syncId={syncId} />
      </ChartPanel>

      {error || isPending ? null : (
        <Spoiler maxHeight={0} showLabel="Show this window as a table" hideLabel="Hide the table">
          <MetricsTable
            columns={[
              { name: 'depth', label: 'Depth', series: depth },
              { name: 'added', label: 'Added (msg/s)', series: added },
              { name: 'acked', label: 'Acked (msg/s)', series: acked },
              { name: 'consumers', label: 'Consumers', series: consumers },
            ]}
            format={(name, value) =>
              name === 'depth'
                ? formatCount(value)
                : name === 'consumers'
                  ? formatExact(value)
                  : formatRate(value)
            }
          />
        </Spoiler>
      )}

      {/* Not a ChartPanel: latency is a live window rather than persisted history
          (ADR-0032), so it carries its own coverage disclosure and its own height
          instead of borrowing the historical panels' fixed box. */}
      <Card withBorder padding="md" radius="md">
        <Stack gap="xs">
          <Group justify="space-between" align="baseline" wrap="nowrap">
            <Text size="sm" fw={600}>
              Request-reply latency
            </Text>
            <Text size="xs" c="dimmed">
              milliseconds — current live window only
            </Text>
          </Group>
          <LatencyPanel clusterId={clusterId} />
        </Stack>
      </Card>
    </Stack>
  );
}
