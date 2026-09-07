import { Group, Paper, SimpleGrid, Text } from '@mantine/core';
import { IconArrowDownRight, IconArrowUpRight, IconMinus } from '@tabler/icons-react';

import styles from './StatRow.module.css';

/**
 * The four numbers an operator arrives for, stated rather than inferred from the
 * right-hand edge of a line (ADR-0055).
 *
 * A metric with no recent sample says so. Rendering a dash as `0` is the
 * dangerous version: on this page a zero ingress rate and an unsampled ingress
 * rate mean entirely different things and lead to opposite actions.
 */
export interface Stat {
  label: string;
  unit: string;
  /** Current value, or `null` when nothing recent enough exists to state one. */
  value: number | null;
  /** Value at the start of the window, for stating movement. */
  since: number | null;
  format: (value: number) => string;
}

export function StatRow({ stats }: { stats: Stat[] }) {
  return (
    <SimpleGrid cols={{ base: 2, md: 4 }} spacing="sm">
      {stats.map((stat) => (
        <StatTile key={stat.label} stat={stat} />
      ))}
    </SimpleGrid>
  );
}

function StatTile({ stat }: { stat: Stat }) {
  const { label, unit, value, since, format } = stat;
  const delta = value !== null && since !== null ? value - since : null;

  return (
    <Paper withBorder p="sm" radius="md">
      <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
        {label}
      </Text>
      {value === null ? (
        <Text size="lg" c="dimmed" mt={4}>
          Not sampled
        </Text>
      ) : (
        <Group gap="xs" align="baseline" mt={4} wrap="nowrap">
          <Text size="xl" fw={600} className={styles.value}>
            {format(value)}
          </Text>
          <Text size="xs" c="dimmed">
            {unit}
          </Text>
        </Group>
      )}
      <Delta delta={delta} format={format} />
    </Paper>
  );
}

/**
 * Movement across the window. The direction is a word and an arrow, never the
 * colour alone — and it carries no colour at all, because a rising queue depth is
 * not by itself something wrong.
 */
function Delta({ delta, format }: { delta: number | null; format: (value: number) => string }) {
  if (delta === null) {
    return (
      <Text size="xs" c="dimmed" mt={2}>
        no comparison in this window
      </Text>
    );
  }
  const flat = Math.abs(delta) < Number.EPSILON;
  const Icon = flat ? IconMinus : delta > 0 ? IconArrowUpRight : IconArrowDownRight;
  const word = flat ? 'unchanged' : delta > 0 ? 'up' : 'down';
  return (
    <Group gap={4} mt={2} wrap="nowrap">
      <Icon size={14} aria-hidden="true" />
      <Text size="xs" c="dimmed" className={styles.value}>
        {word}
        {flat ? '' : ` ${format(Math.abs(delta))}`} over the window
      </Text>
    </Group>
  );
}
