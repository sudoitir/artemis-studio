import { Alert, Stack, Text } from '@mantine/core';
import { BarChart } from '@mantine/charts';

import { useRrStats } from '../api/client.ts';

/**
 * p50/p95/p99 per traced address. The sampling caveat and coverage estimate
 * sit next to the chart, not in a tooltip (request-reply-tracing spec —
 * latency is never shown without its coverage).
 */
export function LatencyPanel({ clusterId }: { clusterId: string }) {
  const stats = useRrStats(clusterId);
  const addresses = stats.data?.addresses ?? [];

  if (stats.isPending) {
    return (
      <Text size="sm" c="dimmed">
        Loading…
      </Text>
    );
  }

  if (addresses.length === 0) {
    return (
      <Text size="sm" c="dimmed">
        No completed flows yet — latency appears once at least one traced request has been
        answered.
      </Text>
    );
  }

  const data = addresses.map((a) => ({
    address: a.address,
    p50: a.p50Ms ?? 0,
    p95: a.p95Ms ?? 0,
    p99: a.p99Ms ?? 0,
  }));

  return (
    <Stack gap="md">
      <Alert color="blue" variant="light" title="Sampled, not exhaustive">
        Latency is measured only on requests Studio happened to observe — a request that completes
        faster than the sample interval is never seen, which biases these numbers toward slower
        flows.{' '}
        {addresses.map((a) => (
          <Text key={a.address} span size="xs" c="dimmed">
            {a.address}:{' '}
            {a.coverageRatio != null
              ? `~${Math.round(a.coverageRatio * 100)}% of requests observed`
              : 'coverage unknown'}
            .{' '}
          </Text>
        ))}
      </Alert>
      <BarChart
        h={280}
        data={data}
        dataKey="address"
        series={[
          { name: 'p50', color: 'var(--as-chart-1)' },
          { name: 'p95', color: 'var(--as-chart-4)' },
          { name: 'p99', color: 'var(--as-chart-threshold)' },
        ]}
        valueFormatter={(v) => `${v}ms`}
        withLegend
      />
    </Stack>
  );
}
