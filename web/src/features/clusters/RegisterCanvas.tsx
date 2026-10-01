import { Stack, Text } from '@mantine/core';

import { Notice } from '../../ui/Notice.tsx';
import type { HealthView, RegisterPreview } from './api.ts';
import { layout } from './layout.ts';
import { TopologyCanvas } from './TopologyCanvas.tsx';
import styles from './RegisterCanvas.module.css';

/** Nothing is registered yet, so no health is known: layout() draws every node as a neutral mark. */
const UNKNOWN_HEALTH: HealthView = {
  clusterId: 'preview',
  level: 'UNKNOWN',
  liveEndpointNames: [],
  splitBrain: 'NONE',
  replicationBehind: false,
  notes: [],
};

/**
 * The topology a passing check discovered, drawn by the same layout() and TopologyCanvas as the
 * cluster's own Topology page. Before a check, the same box says what will appear in it, so the
 * dialog never changes size when the check lands.
 */
export function RegisterCanvas({ preview, stale }: Readonly<{ preview: RegisterPreview | undefined; stale: boolean }>) {
  if (!preview) {
    return (
      <div className={styles.placeholder}>
        <Text size="sm" fw={600}>
          Topology preview
        </Text>
        <Text size="sm" c="dimmed">
          Check connection draws the brokers Studio finds here, before anything is saved.
        </Text>
      </div>
    );
  }
  return (
    <Stack gap="xs">
      <Notice title={stale ? 'Changed since you checked' : 'Discovered topology'} tone={stale ? 'warning' : 'info'}>
        {stale
          ? 'Run Check connection again to preview the current values before registering.'
          : 'This is what will be saved. Nothing is saved yet.'}
      </Notice>
      <div className={styles.previewCanvas} data-stale={stale || undefined}>
        <TopologyCanvas model={layout(preview.topology, UNKNOWN_HEALTH)} interactive={!stale} height="100%" />
      </div>
    </Stack>
  );
}
