import { Alert, Group, Paper, Skeleton, Stack, Text } from '@mantine/core';

import { useQueueHealth } from './api.ts';
import { HealthVerdict } from './HealthVerdict.tsx';
import { formatCount, formatDuration, formatRate, trendPhrase, verdictCopy } from './verdict.ts';

/**
 * One queue's consumer health in its detail drawer (`queue.detail.panels`).
 *
 * It sits above the metrics history panel and links nowhere of its own: the
 * charts already exist on the metrics view, and redrawing them here would be a
 * second answer to a question already answered.
 */
export function QueueHealthPanel({
  clusterId,
  queueName,
}: {
  clusterId: string;
  queueName: string;
  onClose: () => void;
}) {
  const query = useQueueHealth(clusterId, queueName);

  if (query.isPending) {
    return <Skeleton height={96} radius="md" />;
  }

  if (query.isError) {
    // Unreachable is not healthy, and not empty either.
    return (
      <Alert color="gray" title="Consumer health is unavailable">
        {query.error.message} — the verdict for this queue could not be read, so nothing here
        says whether its consumers are keeping up.
      </Alert>
    );
  }

  const row = query.data;
  if (!row) {
    return null;
  }

  const copy = verdictCopy(row.verdict);
  const eta = formatDuration(row.drainEtaSeconds);
  const sampleAge = formatDuration(row.sampleSpanSeconds);

  return (
    <Paper withBorder p="sm" radius="md">
      <Stack gap={6}>
        <Group gap="xs" justify="space-between" wrap="nowrap">
          <Text size="xs" fw={600} c="dimmed" tt="uppercase">
            Consumer health
          </Text>
          <HealthVerdict row={row} />
        </Group>

        <Text size="sm">{copy.headline}</Text>
        <Text size="xs" c="dimmed">
          {row.cause}
        </Text>

        <Group gap="lg" wrap="wrap">
          <Figure label="Arriving" value={formatRate(row.addRate)} />
          <Figure label="Acknowledged" value={formatRate(row.ackRate)} />
          <Figure label="Per consumer" value={formatRate(row.ackRatePerConsumer)} />
          <Figure label="In flight" value={formatCount(row.delivering)} />
        </Group>

        <Text size="xs" c="dimmed">
          {trendPhrase(row)}
          {eta ? ` · clears in about ${eta}` : ''}
          {sampleAge ? ` · measured over ${sampleAge}` : ''}
        </Text>

        {row.nodesPresent < row.nodesTotal ? (
          <Text size="xs" c="dimmed">
            Present on {row.nodesPresent} of {row.nodesTotal} nodes; these numbers cover only the
            nodes reporting it.
          </Text>
        ) : null}

        {row.source === 'BROKER' ? (
          <Text size="xs" c="dimmed">
            Reported by the broker's own slow-consumer detection
            {row.brokerConsumerName ? ` for ${row.brokerConsumerName}` : ''}.
          </Text>
        ) : null}
      </Stack>
    </Paper>
  );
}

function Figure({ label, value }: { label: string; value: string }) {
  return (
    <Stack gap={0}>
      <Text size="xs" c="dimmed">
        {label}
      </Text>
      <Text size="sm" fw={600}>
        {value}
      </Text>
    </Stack>
  );
}
