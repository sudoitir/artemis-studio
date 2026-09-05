import { useMemo } from 'react';
import { Alert, Group, Stack, Text, Title } from '@mantine/core';
import { useParams, useSearch } from '@tanstack/react-router';
import dayjs from 'dayjs';

import { useMetrics } from '../api/client.ts';
import type { MetricRange } from './ranges.ts';
import { RangePicker } from './RangePicker.tsx';
import { DepthChart } from './DepthChart.tsx';
import { ThroughputChart } from './ThroughputChart.tsx';
import { ConsumersChart } from './ConsumersChart.tsx';
import { LatencyPanel } from '../rr/LatencyPanel.tsx';

const STEP_HINT: Record<MetricRange, string> = {
  '15m': 'PT15S',
  '1h': 'PT1M',
  '6h': 'PT5M',
  '24h': 'PT15M',
  '7d': 'PT1H',
};

const RANGE_MS: Record<MetricRange, number> = {
  '15m': 15 * 60_000,
  '1h': 60 * 60_000,
  '6h': 6 * 60 * 60_000,
  '24h': 24 * 60 * 60_000,
  '7d': 7 * 24 * 60 * 60_000,
};

/**
 * Cluster-wide historical metrics: depth, throughput, consumers, and request-reply
 * latency, sharing one crosshair (metrics spec). A relative range polls at its own
 * bucket cadence — never the global 5s default a 7-day chart would drown in; an
 * absolute (deep-linked) range never polls at all.
 */
export function MetricsView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as { range?: MetricRange; from?: string; to?: string };

  const live = !search.from && !search.to;
  const range = search.range ?? '1h';

  const { from, to } = useMemo(() => {
    if (!live) return { from: search.from!, to: search.to! };
    const now = dayjs();
    return { from: now.subtract(RANGE_MS[range], 'millisecond').toISOString(), to: now.toISOString() };
  }, [live, range, search.from, search.to]);

  const metrics = useMetrics(
    clusterId,
    {
      metrics: ['messageCount', 'consumerCount', 'messagesAdded', 'messagesAcked'],
      subjectType: 'CLUSTER',
      from,
      to,
      step: live ? STEP_HINT[range] : undefined,
    },
    live ? Math.max(15_000, RANGE_MS[range] / 60) : false,
  );

  const byName = (name: string) => metrics.data?.series.find((s) => s.metric === name);
  const syncId = `cluster-metrics-${clusterId}`;

  return (
    <Stack gap="lg">
      <Group justify="space-between" align="center">
        <Title order={3}>Metrics</Title>
        <RangePicker />
      </Group>

      {metrics.data?.truncated ? (
        <Alert color="gray" variant="light" title="Window adjusted">
          The requested resolution or range was wider than this cluster's retention or
          sampling cadence allows; the chart below shows the {metrics.data.step} bucket
          Studio actually used.
        </Alert>
      ) : null}

      <Stack gap="xs">
        <Text size="sm" fw={600}>
          Depth
        </Text>
        <DepthChart series={byName('messageCount')} syncId={syncId} />
      </Stack>

      <Stack gap="xs">
        <Text size="sm" fw={600}>
          Throughput
        </Text>
        <ThroughputChart added={byName('messagesAdded')} acked={byName('messagesAcked')} syncId={syncId} />
      </Stack>

      <Stack gap="xs">
        <Text size="sm" fw={600}>
          Consumers
        </Text>
        <ConsumersChart series={byName('consumerCount')} syncId={syncId} />
      </Stack>

      <Stack gap="xs">
        <Text size="sm" fw={600}>
          Request-reply latency
        </Text>
        <LatencyPanel clusterId={clusterId} />
      </Stack>
    </Stack>
  );
}
