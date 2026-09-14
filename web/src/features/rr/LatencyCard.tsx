import { Card, Group, Stack, Text } from '@mantine/core';

import { LatencyPanel } from './LatencyPanel.tsx';

/**
 * Request-reply latency at the foot of a cluster's metrics view (`metrics.panels`). Not a chart
 * panel: latency is a live window rather than persisted history (ADR-0032), so it carries its own
 * coverage disclosure and its own height instead of borrowing the historical panels' fixed box.
 */
export function LatencyCard({ clusterId }: { clusterId: string }) {
  return (
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
  );
}
