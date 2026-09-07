import { Alert, List, Spoiler, Stack, Text } from '@mantine/core';
import { BarChart } from '@mantine/charts';

import { useRrStats } from '../api/client.ts';

/** Coverage lines shown before the list is folded away — one per traced address. */
const COVERAGE_VISIBLE = 6;

/**
 * p50/p95/p99 per traced address. The sampling caveat and coverage estimate
 * sit next to the chart, not in a tooltip (request-reply-tracing spec —
 * latency is never shown without its coverage).
 */
export function LatencyPanel({ clusterId }: { clusterId: string }) {
  const stats = useRrStats(clusterId);
  const addresses = stats.data?.addresses ?? [];

  if (stats.isError) {
    return (
      <Alert color="red" variant="light" title={stats.error.title}>
        {stats.error.message} — latency could not be read, which is not the same as
        there being no traced flows.
      </Alert>
    );
  }

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

  // A percentile with no value is omitted, never coerced to zero: an absent
  // number reads as "instant", which is the most dangerous reading of a latency
  // chart there is.
  const data = addresses.map((a) => ({
    address: a.address,
    p50: a.p50Ms ?? undefined,
    p95: a.p95Ms ?? undefined,
    p99: a.p99Ms ?? undefined,
  }));

  return (
    <Stack gap="md">
      <Alert color="blue" variant="light" title="Sampled, not exhaustive">
        Latency is measured only on requests Studio happened to observe — a request that completes
        faster than the sample interval is never seen, which biases these numbers toward slower
        flows.{' '}
        <Spoiler
          maxHeight={COVERAGE_VISIBLE * 22}
          showLabel={`Show coverage for all ${addresses.length} addresses`}
          hideLabel="Show fewer"
        >
          <List size="xs" spacing={2} mt="xs">
            {addresses.map((a) => (
              <List.Item key={a.address}>
                <Text span size="xs" c="dimmed">
                  {a.address}:{' '}
                  {a.coverageRatio != null
                    ? `~${Math.round(a.coverageRatio * 100)}% of requests observed`
                    : 'coverage unknown'}
                </Text>
              </List.Item>
            ))}
          </List>
        </Spoiler>
      </Alert>
      <BarChart
        h={280}
        data={data}
        dataKey="address"
        // One hue, light to dark: p50/p95/p99 is an ordered magnitude, not three
        // identities, and the status colours it used to borrow said a slow tail
        // was an error before anyone had decided that it was.
        series={[
          { name: 'p50', color: 'var(--as-chart-seq-1)' },
          { name: 'p95', color: 'var(--as-chart-seq-2)' },
          { name: 'p99', color: 'var(--as-chart-seq-3)' },
        ]}
        valueFormatter={(v) => `${v}ms`}
        withLegend
      />
    </Stack>
  );
}
