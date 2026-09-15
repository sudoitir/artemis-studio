import { Paper, SimpleGrid, Text } from '@mantine/core';

import type { FlowKpis as Kpis } from './api.ts';
import { formatCount, totalRateLabel } from './flowFormat.ts';
import classes from './FlowView.module.css';

interface Tile {
  label: string;
  value: string;
  /** Set only when something is wrong; a healthy tile stays neutral. */
  alarm?: boolean;
}

/** The cluster's current totals, leading the view (metrics spec: a view leads with the current values). */
export function FlowKpis({ kpis }: { kpis: Kpis }) {
  const faults = kpis.faults ?? 0;
  const tiles: Tile[] = [
    { label: 'Messages in', value: totalRateLabel(kpis.inRate) },
    { label: 'Messages out', value: totalRateLabel(kpis.outRate) },
    { label: 'Backlog', value: `${formatCount(kpis.backlog ?? 0)} waiting` },
    { label: 'Clients', value: `${formatCount(kpis.clients ?? 0)} connected` },
    { label: 'Faults', value: faults === 0 ? 'none' : `${faults} ${faults === 1 ? 'fault' : 'faults'}`, alarm: faults > 0 },
  ];
  return (
    <section aria-label="Totals across every path">
      <SimpleGrid cols={{ base: 2, sm: 3, lg: 5 }} spacing="sm">
        {tiles.map((tile) => (
          <Paper key={tile.label} withBorder p="sm" radius="md">
            <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
              {tile.label}
            </Text>
            <Text size="lg" fw={600} mt={4} className={tile.alarm ? classes.alarm : classes.figure}>
              {tile.value}
            </Text>
          </Paper>
        ))}
      </SimpleGrid>
    </section>
  );
}
