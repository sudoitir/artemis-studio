import { useMemo, useState } from 'react';
import {
  Accordion,
  Alert,
  Badge,
  Group,
  Loader,
  Select,
  Stack,
  Switch,
  Table,
  Text,
  Title,
} from '@mantine/core';
import { useParams } from '@tanstack/react-router';

import { useConfigDiff, useTopology, type ConfigEntryView, type ConfigSectionView } from '../api/client.ts';
import styles from './ConfigDiffView.module.css';

/** Drift is the only class that is a problem; the other two exist so it stays legible. */
function classificationBadge(entry: ConfigEntryView) {
  if (entry.classification === 'EXPECTED') {
    return (
      <Badge size="xs" variant="default" title="Correct by design for two distinct nodes">
        expected
      </Badge>
    );
  }
  if (entry.classification === 'UNCLASSIFIED') {
    return (
      <Badge size="xs" variant="default" title="Not known to be configuration — a runtime counter, or an attribute Studio has not classified">
        unclassified
      </Badge>
    );
  }
  return entry.drift ? (
    <Badge size="xs" color="yellow" variant="light">
      drift
    </Badge>
  ) : null;
}

function SectionTable({ entries }: { entries: ConfigEntryView[] }) {
  if (entries.length === 0) {
    return (
      <Text size="sm" c="dimmed">
        Nothing to compare in this section.
      </Text>
    );
  }
  return (
    <div className={styles.scroll}>
      <Table withRowBorders={false} verticalSpacing={4} className={styles.table}>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Key</Table.Th>
            <Table.Th>Left</Table.Th>
            <Table.Th>Right</Table.Th>
            <Table.Th>Status</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {entries.map((e) => (
            <Table.Tr key={e.key} data-drift={e.drift || undefined}>
              <Table.Td>
                <Text size="xs" ff="monospace">
                  {e.key}
                </Text>
              </Table.Td>
              <Table.Td>
                <Text size="xs" ff="monospace" c={e.left === null ? 'dimmed' : undefined}>
                  {e.left ?? '—'}
                </Text>
              </Table.Td>
              <Table.Td>
                <Text size="xs" ff="monospace" c={e.right === null ? 'dimmed' : undefined}>
                  {e.right ?? '—'}
                </Text>
              </Table.Td>
              <Table.Td>
                {/* The status is a word, never carried by colour alone. */}
                <Group gap={6} wrap="nowrap">
                  <Text size="xs">{e.statusWord}</Text>
                  {classificationBadge(e)}
                </Group>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </div>
  );
}

/**
 * Broker configuration compared across two nodes (ADR-0043). Drift between a
 * primary and its backup is silent until failover, when it is expensive.
 *
 * The screen's job is to make a clean pair *read* as clean: expected differences
 * (a broker's name, its node-local paths) and unclassified keys (runtime counters)
 * are shown but kept out of the drift count, so the operator is not trained to
 * ignore the list.
 */
export function ConfigDiffView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const topology = useTopology(clusterId);
  const [left, setLeft] = useState<string | null>(null);
  const [right, setRight] = useState<string | null>(null);
  const [driftOnly, setDriftOnly] = useState(false);

  const nodeOptions = useMemo(
    () =>
      (topology.data?.nodes ?? []).flatMap((n) =>
        n.endpoints.map((e) => ({ value: e.id, label: e.name, disabled: !e.manageable })),
      ),
    [topology.data],
  );

  const diff = useConfigDiff(clusterId, left, right);

  const sections: ConfigSectionView[] = useMemo(() => {
    const all = diff.data?.sections ?? [];
    if (!driftOnly) return all;
    return all.map((s) => ({ ...s, entries: s.entries.filter((e) => e.drift) }));
  }, [diff.data, driftOnly]);

  return (
    <Stack gap="md">
      <Group justify="space-between" align="flex-end" wrap="wrap">
        <Group gap="xs" align="flex-end">
          <Select
            label="Left node"
            placeholder="Auto"
            data={nodeOptions}
            value={left}
            onChange={setLeft}
            clearable
            w={200}
          />
          <Select
            label="Right node"
            placeholder="Its pair"
            data={nodeOptions}
            value={right}
            onChange={setRight}
            clearable
            w={200}
          />
        </Group>
        <Switch
          label="Drift only"
          checked={driftOnly}
          onChange={(e) => setDriftOnly(e.currentTarget.checked)}
          mb={8}
        />
      </Group>

      {diff.isPending ? <Loader size="sm" /> : null}

      {diff.isError ? (
        <Alert color="red" variant="light" title={diff.error.title}>
          {diff.error.message}
        </Alert>
      ) : null}

      {diff.data ? (
        <>
          <Group gap="xs" wrap="wrap">
            <Title order={4}>
              {diff.data.left.nodeName} ↔ {diff.data.right.nodeName}
            </Title>
            {diff.data.comparable ? (
              <Badge
                variant="light"
                color={diff.data.driftCount > 0 ? 'yellow' : 'gray'}
                title="Differences in configuration keys, excluding expected and unclassified ones"
              >
                {diff.data.driftCount === 0
                  ? 'no drift'
                  : `${diff.data.driftCount} drift${diff.data.driftCount === 1 ? '' : 's'}`}
              </Badge>
            ) : null}
            {[diff.data.left, diff.data.right].map((side) =>
              side.available ? null : (
                <Badge key={side.nodeId} variant="light" color="red">
                  {side.nodeName} unavailable
                </Badge>
              ),
            )}
          </Group>

          {/* Never a half-diff: when a side is unreachable or answers thinly, say so. */}
          {!diff.data.comparable ? (
            <Alert color="yellow" variant="light" title="No comparison shown">
              <Stack gap={4}>
                <Text size="sm">{diff.data.note}</Text>
                {[diff.data.left, diff.data.right]
                  .filter((s) => s.unavailableReason)
                  .map((s) => (
                    <Text key={s.nodeId} size="sm">
                      <strong>{s.nodeName}:</strong> {s.unavailableReason}
                    </Text>
                  ))}
              </Stack>
            </Alert>
          ) : null}

          {diff.data.comparable && diff.data.note ? (
            <Text size="xs" c="dimmed">
              {diff.data.note}
            </Text>
          ) : null}

          {diff.data.comparable ? (
            <Accordion multiple defaultValue={['broker', 'addressSettings']} variant="separated">
              {sections.map((s) => (
                <Accordion.Item key={s.section} value={s.section}>
                  <Accordion.Control>
                    <Group gap="xs">
                      <Text size="sm" fw={600}>
                        {s.label}
                      </Text>
                      <Text size="xs" c="dimmed">
                        {s.entries.length} key{s.entries.length === 1 ? '' : 's'}
                      </Text>
                      {s.driftCount > 0 ? (
                        <Badge size="xs" color="yellow" variant="light">
                          {s.driftCount} drift
                        </Badge>
                      ) : null}
                    </Group>
                  </Accordion.Control>
                  <Accordion.Panel>
                    <SectionTable entries={s.entries} />
                  </Accordion.Panel>
                </Accordion.Item>
              ))}
            </Accordion>
          ) : null}
        </>
      ) : null}
    </Stack>
  );
}
