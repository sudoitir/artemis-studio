import { Card, Group, Stack, Text } from '@mantine/core';

import type { ApiError } from '../api/request.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';

/** Every metric plot is this tall, whatever it is currently able to show. */
export const CHART_HEIGHT = '13.75rem';

/** What stands in for the plot: the failure, the loading placeholder, or the empty statement. */
function chartNotice(
  title: string,
  error: ApiError | null,
  isPending: boolean,
  isEmpty: boolean,
  emptyLabel: string,
): React.ReactNode {
  if (error) {
    return <ErrorState error={error} variant="inline" />;
  }
  if (isPending) return <LoadingState label={`Loading ${title}`} blockSize={CHART_HEIGHT} />;
  if (!isEmpty) return null;
  return <EmptyState kind="empty" title="Nothing to plot in this window" description={emptyLabel} />;
}

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
}: Readonly<{
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
}>) {
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
          {chartNotice(title, error, isPending, isEmpty, emptyLabel) ?? children}
        </div>
      </Stack>
    </Card>
  );
}
