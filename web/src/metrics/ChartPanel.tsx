import { Alert, Card, Group, Skeleton, Stack, Text } from '@mantine/core';

import type { ApiError } from '../api/client.ts';

/** Every metric plot is this tall, whatever it is currently able to show. */
export const CHART_HEIGHT = 220;

/**
 * A titled panel around one metric plot, and the three states it can be in.
 *
 * Failure, emptiness and loading are rendered as three different things at one
 * fixed height (ADR-0055). Both halves of that matter:
 *
 * - a metrics read that returned 500 used to render "No samples in this window
 *   yet", which presents an outage as a fact about the cluster — and a flat empty
 *   chart during an incident reads as "quiet", the most expensive possible
 *   misreading;
 * - a panel that collapses while loading and expands when data arrives moves the
 *   page under a pointer already travelling towards something else.
 */
export function ChartPanel({
  title,
  unit,
  isPending,
  error,
  isEmpty,
  emptyLabel,
  note,
  children,
}: {
  title: string;
  unit: string;
  isPending: boolean;
  error: ApiError | null;
  /** True when the read succeeded and carried nothing for this window. */
  isEmpty: boolean;
  emptyLabel: string;
  /** A coverage caveat that belongs beside the title, not inside a tooltip. */
  note?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <Card withBorder padding="md" radius="md">
      <Stack gap="xs">
        <Group justify="space-between" align="baseline" wrap="nowrap">
          <Text size="sm" fw={600}>
            {title}
          </Text>
          <Text size="xs" c="dimmed">
            {unit}
          </Text>
        </Group>
        {note}
        <div style={{ blockSize: CHART_HEIGHT }}>
          {error ? (
            <Alert color="red" variant="light" title={error.title} h="100%">
              {error.message} — this window could not be read, which is not the same
              as there being nothing in it.
            </Alert>
          ) : isPending ? (
            <Skeleton height={CHART_HEIGHT} radius="sm" />
          ) : isEmpty ? (
            <Group h="100%" justify="center">
              <Text size="sm" c="dimmed" ta="center" maw="32rem">
                {emptyLabel}
              </Text>
            </Group>
          ) : (
            children
          )}
        </div>
      </Stack>
    </Card>
  );
}
