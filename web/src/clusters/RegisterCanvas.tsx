import { Alert, Stack, Text } from '@mantine/core';

import type { RegisterPreview, TopologyView } from '../api/client.ts';
import { layout } from '../topology/layout.ts';
import { TopologyCanvas } from '../topology/TopologyCanvas.tsx';
import { EXAMPLE_HEALTH, EXAMPLES, type ExampleShape } from './examples.ts';
import styles from './RegisterCanvas.module.css';

/**
 * Two states in one box, so the page never reflows: example topology cards
 * before a successful check, the operator's real discovered topology after —
 * both drawn by the same `layout()` + `TopologyCanvas` the live cluster screen
 * uses, never a forked visual language.
 */
export function RegisterCanvas({
  preview,
  stale,
  shape,
  onSelectShape,
}: {
  preview: RegisterPreview | undefined;
  stale: boolean;
  shape: ExampleShape | null;
  onSelectShape: (shape: ExampleShape) => void;
}) {
  if (preview) {
    return <PreviewCanvas topology={preview.topology} stale={stale} />;
  }
  return <ExampleCards shape={shape} onSelectShape={onSelectShape} />;
}

function PreviewCanvas({ topology, stale }: { topology: TopologyView; stale: boolean }) {
  const model = layout(topology, EXAMPLE_HEALTH);
  return (
    <Stack gap="xs">
      <Alert color={stale ? 'yellow' : 'pine'} variant="light" title={stale ? 'Changed since you checked' : 'Discovered topology'}>
        <Text size="sm">
          {stale
            ? 'Run Check connection again to preview the current values before registering.'
            : 'This is what will be saved. Nothing is saved yet.'}
        </Text>
      </Alert>
      <div className={styles.previewCanvas} data-stale={stale || undefined}>
        <TopologyCanvas model={model} interactive={!stale} />
      </div>
    </Stack>
  );
}

function ExampleCards({
  shape,
  onSelectShape,
}: {
  shape: ExampleShape | null;
  onSelectShape: (shape: ExampleShape) => void;
}) {
  return (
    <Stack gap="xs">
      <Text size="sm" c="dimmed">
        Which of these looks like your setup? These are examples for orientation —
        nothing is registered until you press Register cluster.
      </Text>
      <div className={styles.cards}>
        {EXAMPLES.map((ex) => {
          const model = layout(ex.topology, EXAMPLE_HEALTH);
          return (
            <button
              key={ex.shape}
              type="button"
              className={styles.card}
              data-selected={shape === ex.shape || undefined}
              aria-pressed={shape === ex.shape}
              onClick={() => onSelectShape(ex.shape)}
            >
              <Text size="xs" fw={600}>
                {ex.title}
              </Text>
              <Text size="xs" c="dimmed">
                Example — not your cluster
              </Text>
              <div className={styles.cardCanvas} aria-hidden="true">
                <TopologyCanvas model={model} interactive={false} />
              </div>
            </button>
          );
        })}
      </div>
    </Stack>
  );
}
