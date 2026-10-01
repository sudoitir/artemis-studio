import { useMemo, useState } from 'react';
import { Accordion, Select, Switch, Text } from '@mantine/core';
import { useParams } from '@tanstack/react-router';

import { useConfigDiff, type ConfigSectionView } from './api.ts';
import { useTopology } from '../clusters/index.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { Section } from '../../ui/Section.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import { Toolbar } from '../../ui/Toolbar.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { diffEntryColumns } from './configDiffColumns.tsx';
import classes from './ConfigDiffView.module.css';

/** One section's keys, side by side: a table, or what its emptiness means. */
function SectionTable({
  section,
  driftOnly,
  onShowAll,
}: Readonly<{ section: ConfigSectionView; driftOnly: boolean; onShowAll: () => void }>) {
  const columns = useMemo(diffEntryColumns, []);
  if (section.entries.length > 0) {
    return (
      <DataTable
        variant="static"
        label={`${section.label} configuration of the two nodes`}
        columns={columns}
        data={section.entries}
        rowKey={(e) => e.key}
        storageKey="brokerconfig.diff"
        rowClassName={(e) => (e.drift ? classes.drift : undefined)}
        height={{ maxRows: section.entries.length }}
        empty={null}
      />
    );
  }
  // Filtered-empty is not empty: with "Drift only" on, say that, and offer the whole section.
  return driftOnly ? (
    <EmptyState
      kind="filtered"
      title="No drift in this section"
      description="Drift only is on, so keys that agree are not listed."
      onClearFilters={onShowAll}
    />
  ) : (
    <Text size="sm" c="dimmed">
      Nothing to compare in this section.
    </Text>
  );
}

type DiffData = NonNullable<ReturnType<typeof useConfigDiff>['data']>;

function driftLabel(count: number): string {
  if (count === 0) return 'no drift';
  return `${count} drift${count === 1 ? '' : 's'}`;
}

/** Why the pair cannot be compared: the note, and each side that did not answer with its reason. */
function NoComparison({ data }: Readonly<{ data: DiffData }>) {
  const sides = [data.left, data.right];
  const reasons = (
    <>
      {data.note ? <Text size="sm">{data.note}</Text> : null}
      {sides
        .filter((s) => s.unavailableReason)
        .map((s) => (
          <Text key={s.nodeId} size="sm">
            <strong>{s.nodeName}:</strong> {s.unavailableReason}
          </Text>
        ))}
    </>
  );
  const unreachable = sides.filter((s) => !s.available).map((s) => s.nodeName);
  // Never a half-diff: when a side is unreachable or answers thinly, say so.
  return unreachable.length > 0 ? (
    <EmptyState kind="unreachable" title="No comparison shown" description={reasons} nodes={unreachable} />
  ) : (
    <EmptyState kind="empty" title="No comparison shown" description={reasons} />
  );
}

/** The comparison itself: the pair, why it cannot be compared when it cannot, and the sections. */
function DiffResult({
  data,
  sections,
  driftOnly,
  onShowAll,
}: Readonly<{ data: DiffData; sections: ConfigSectionView[]; driftOnly: boolean; onShowAll: () => void }>) {
  return (
    <Section
      title={`${data.left.nodeName} ↔ ${data.right.nodeName}`}
      description={
        data.comparable ? (
          <>
            <div>Differences in configuration keys, excluding expected and unclassified ones.</div>
            {data.note ? <div>{data.note}</div> : null}
          </>
        ) : undefined
      }
      actions={
        <>
          {data.comparable ? (
            <StatusBadge tone={data.driftCount > 0 ? 'warning' : 'neutral'}>{driftLabel(data.driftCount)}</StatusBadge>
          ) : null}
          {[data.left, data.right].map((side) =>
            side.available ? null : (
              <StatusBadge key={side.nodeId} tone="danger">
                {`${side.nodeName} unavailable`}
              </StatusBadge>
            ),
          )}
        </>
      }
    >
      {data.comparable ? (
        <Accordion multiple defaultValue={['broker', 'addressSettings']} variant="separated" order={3}>
          {sections.map((s) => (
            <Accordion.Item key={s.section} value={s.section}>
              <Accordion.Control>
                <span className={classes.sectionHead}>
                  <Text size="sm" fw={600} component="span">
                    {s.label}
                  </Text>
                  <Text size="sm" c="dimmed" component="span">
                    {s.entries.length} key{s.entries.length === 1 ? '' : 's'}
                  </Text>
                  {s.driftCount > 0 ? <StatusBadge tone="warning">{`${s.driftCount} drift`}</StatusBadge> : null}
                </span>
              </Accordion.Control>
              <Accordion.Panel>
                <SectionTable section={s} driftOnly={driftOnly} onShowAll={onShowAll} />
              </Accordion.Panel>
            </Accordion.Item>
          ))}
        </Accordion>
      ) : (
        <NoComparison data={data} />
      )}
    </Section>
  );
}

/**
 * Broker configuration compared across two nodes (ADR-0043). Drift between a primary and its backup is
 * silent until failover, when it is expensive.
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
    <Page>
      <PageHeader
        title="Config diff"
        description="Broker configuration compared across two nodes. Drift between a primary and its backup is silent until failover, when it is expensive."
      />

      <Toolbar
        label="Nodes to compare"
        start={
          <>
            <Select
              label="Left node"
              placeholder="Auto"
              data={nodeOptions}
              value={left}
              onChange={setLeft}
              clearable
              w="12.5rem"
            />
            <Select
              label="Right node"
              placeholder="Its pair"
              data={nodeOptions}
              value={right}
              onChange={setRight}
              clearable
              w="12.5rem"
            />
          </>
        }
        end={<Switch label="Drift only" checked={driftOnly} onChange={(e) => setDriftOnly(e.currentTarget.checked)} />}
      />

      {topology.isError ? <ErrorState error={topology.error} onRetry={() => void topology.refetch()} /> : null}

      {diff.isPending ? <LoadingState label="Comparing the nodes" blockSize="20rem" /> : null}

      {diff.isError ? <ErrorState error={diff.error} onRetry={() => void diff.refetch()} /> : null}

      {diff.data ? (
        <DiffResult data={diff.data} sections={sections} driftOnly={driftOnly} onShowAll={() => setDriftOnly(false)} />
      ) : null}
    </Page>
  );
}
