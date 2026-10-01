import { useMemo, useState } from 'react';
import { Stack, Text } from '@mantine/core';

import {
  useBrokerConfigApplies,
  useBrokerConfigApply,
  useBrokerConfigRevisions,
  type ConfigCatalogueView,
  type ConfigDeclarationView,
  type ConfigRevisionView,
} from './api.ts';
import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Section } from '../../ui/Section.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { ApplyResult } from './ApplyResult.tsx';
import { applyColumns, diffColumns, diffDocuments, revisionColumns, type DiffRow } from './historyColumns.tsx';

function diffSummary(diff: DiffRow[], compared: number, current: number): string {
  if (diff.length === 0) return `Revision ${compared} and revision ${current} declare the same thing.`;
  const changedItems = new Set(diff.map((d) => d.item)).size;
  return `${changedItems} item${changedItems === 1 ? '' : 's'} differ between revision ${compared} and revision ${current}.`;
}

/** The rows that differ between an earlier revision and the current one. */
function CompareDiff({
  diff,
  compare,
  current,
}: Readonly<{ diff: DiffRow[]; compare: ConfigRevisionView; current: number }>) {
  const columns = useMemo(() => diffColumns(compare.revision, current), [compare.revision, current]);
  return (
    <Section headingLevel={3} title={`Revision ${compare.revision} compared with revision ${current}`}>
      <Text size="sm" c="dimmed" role="status">
        {diffSummary(diff, compare.revision, current)}
      </Text>
      {diff.length > 0 ? (
        <DataTable
          variant="static"
          label={`Revision ${compare.revision} compared with revision ${current}`}
          columns={columns}
          data={diff}
          rowKey={(d) => d.id}
          storageKey="brokerconfig.history.diff"
          height={{ maxRows: diff.length }}
          empty={null}
        />
      ) : null}
    </Section>
  );
}

/** Revisions and applies: who, when, what, with a link into the audit log for every real apply. */
export function HistoryTab({
  declaration,
  catalogue,
}: Readonly<{
  declaration: ConfigDeclarationView;
  catalogue?: ConfigCatalogueView;
}>) {
  const zone = useDisplayZone();
  const revisions = useBrokerConfigRevisions(declaration.clusterId);
  const applies = useBrokerConfigApplies(declaration.clusterId);
  const [compare, setCompare] = useState<ConfigRevisionView | null>(null);
  const [openApply, setOpenApply] = useState<number | null>(null);
  const detail = useBrokerConfigApply(declaration.clusterId, openApply);

  const current = revisions.data?.find((r) => r.revision === declaration.revision);
  const diff = compare && current ? diffDocuments(compare.document, current.document, catalogue) : [];

  const revisionCols = useMemo(
    () =>
      revisionColumns(zone, declaration.revision, compare?.revision, (r) =>
        setCompare((c) => (c?.revision === r.revision ? null : r)),
      ),
    [zone, declaration.revision, compare?.revision],
  );
  const applyCols = useMemo(
    () => applyColumns(zone, declaration.clusterId, openApply, (id) => setOpenApply((o) => (o === id ? null : id))),
    [zone, declaration.clusterId, openApply],
  );

  // Both lists arrive before either is drawn: a table that loads on its own and then shrinks to its few
  // rows would pull the section below it up the page.
  if (revisions.isPending || applies.isPending) {
    return <LoadingState label="Loading the history" blockSize="24rem" />;
  }

  return (
    <Stack gap="xl">
      <Section
        title="Revisions"
        description="Every saved revision of the declaration, newest first, with who saved it and from where."
      >
        <DataTable
          variant="static"
          label="Revisions"
          columns={revisionCols}
          data={revisions.data ?? []}
          rowKey={(r) => String(r.revision)}
          storageKey="brokerconfig.history.revisions"
          height={{ maxRows: 12 }}
          error={
            revisions.isError ? (
              <ErrorState error={revisions.error} onRetry={() => void revisions.refetch()} />
            ) : undefined
          }
          empty={
            <EmptyState
              kind="empty"
              title="No revision has been saved yet"
              description="A revision is one saved copy of the declaration. Adopt what the cluster runs, import broker.xml or add an entry, and the first one is saved."
            />
          }
        />
        {compare ? <CompareDiff diff={diff} compare={compare} current={declaration.revision} /> : null}
      </Section>

      <Section
        title="Applies"
        description="Every preview and apply of the declaration, newest first, with how it ended."
      >
        <DataTable
          variant="static"
          label="Applies"
          columns={applyCols}
          data={applies.data ?? []}
          rowKey={(a) => String(a.id)}
          storageKey="brokerconfig.history.applies"
          height={{ maxRows: 12 }}
          error={
            applies.isError ? <ErrorState error={applies.error} onRetry={() => void applies.refetch()} /> : undefined
          }
          empty={
            <EmptyState
              kind="empty"
              title="Nothing has been applied or previewed yet."
              description="An apply writes the declaration to the live nodes, canary first; a preview plans one without writing. Each is listed here once it has run."
            />
          }
        />
        {openApply === null ? null : (
          <Section headingLevel={3} title={`Apply ${openApply}`}>
            {detail.isError ? <ErrorState error={detail.error} onRetry={() => void detail.refetch()} /> : null}
            {detail.isPending ? <LoadingState label="Loading the apply" blockSize="8rem" /> : null}
            {detail.data ? (
              <ApplyResult
                outcome={{
                  applyId: detail.data.apply.id,
                  dryRun: detail.data.apply.dryRun,
                  outcome: detail.data.apply.outcome,
                  revision: declaration.revision,
                  plan: detail.data.plan,
                  nodes: detail.data.nodes,
                  stepCap: 0,
                  overCap: false,
                  summary: detail.data.apply.summary ?? '',
                  auditEventId: detail.data.apply.auditEventId,
                }}
              />
            ) : null}
          </Section>
        )}
      </Section>
    </Stack>
  );
}
