import { useState, type ReactNode } from 'react';
import { Anchor, Button, Group, Skeleton, Stack, Table, Text } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import {
  useBrokerConfigApplies,
  useBrokerConfigApply,
  useBrokerConfigRevisions,
  type ConfigCatalogueView,
  type ConfigDeclarationView,
  type ConfigDocumentView,
  type ConfigRevisionView,
} from './api.ts';
import { absoluteLabel } from '../../kernel/time/time.ts';
import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { ApplyResult } from './ApplyResult.tsx';
import classes from './Configuration.module.css';
import { documentItems } from './pretty.ts';
import { applyOutcomeWords } from './words.ts';

/**
 * What changed between two documents, item by item and key by key: an item
 * added or removed is one row stating so; an item present in both lists only
 * the keys whose value differs, so the operator reads the change, not the JSON.
 */
interface DiffRow {
  item: string;
  key: string;
  left: string;
  right: string;
}

const declaredLabel = (keys: number) => `declared (${keys} ${keys === 1 ? 'key' : 'keys'})`;

/** An item declared on one side only is one row stating so. */
function addedOrRemoved(item: string, l: unknown[] | undefined, r: unknown[] | undefined): DiffRow {
  if (!l) return { item, key: '', left: 'not declared', right: declaredLabel(r!.length) };
  return { item, key: '', left: declaredLabel(l.length), right: 'not declared' };
}

function diffDocuments(a: ConfigDocumentView, b: ConfigDocumentView, catalogue?: ConfigCatalogueView): DiffRow[] {
  const left = documentItems(a, catalogue);
  const right = documentItems(b, catalogue);
  const out: DiffRow[] = [];
  for (const item of [...new Set([...left.keys(), ...right.keys()])].sort((a, b) => a.localeCompare(b))) {
    const l = left.get(item);
    const r = right.get(item);
    if (!l || !r) {
      out.push(addedOrRemoved(item, l, r));
      continue;
    }
    const lm = new Map(l.map((x) => [x.key, x.value]));
    const rm = new Map(r.map((x) => [x.key, x.value]));
    for (const key of [...new Set([...lm.keys(), ...rm.keys()])].sort((a, b) => a.localeCompare(b))) {
      if (lm.get(key) !== rm.get(key)) {
        out.push({ item, key, left: lm.get(key) ?? 'not declared', right: rm.get(key) ?? 'not declared' });
      }
    }
  }
  return out;
}

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
  return (
    <Stack gap={4}>
      <Text size="xs" c="dimmed">
        {diffSummary(diff, compare.revision, current)}
      </Text>
      {diff.length > 0 ? (
        <Table fz="xs" verticalSpacing={4} withTableBorder>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Item</Table.Th>
              <Table.Th>Key</Table.Th>
              <Table.Th>Revision {compare.revision}</Table.Th>
              <Table.Th>Revision {current}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {diff.map((d, i) => (
              <Table.Tr key={`${d.item}/${d.key}`}>
                {/* The item is named once per group, the way a reader scans a diff. */}
                <Table.Td>{i === 0 || diff[i - 1].item !== d.item ? d.item : ''}</Table.Td>
                <Table.Td className={classes.kvKey}>{d.key}</Table.Td>
                <Table.Td className={classes.compare}>{d.left}</Table.Td>
                <Table.Td className={classes.compare}>{d.right}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      ) : null}
    </Stack>
  );
}

type Applies = NonNullable<ReturnType<typeof useBrokerConfigApplies>['data']>;

/** What stands in for the applies table while it loads or when nothing was ever applied. */
function appliesNotice(applies: ReturnType<typeof useBrokerConfigApplies>): ReactNode {
  if (applies.isPending) return <Skeleton height={60} />;
  if ((applies.data ?? []).length === 0) {
    return (
      <Text size="xs" c="dimmed">
        Nothing has been applied or previewed yet.
      </Text>
    );
  }
  return null;
}

function AppliesTable({
  applies,
  clusterId,
  openApply,
  onToggle,
}: Readonly<{ applies: Applies; clusterId: string; openApply: number | null; onToggle: (id: number) => void }>) {
  return (
    <Table fz="xs" verticalSpacing={4}>
      <Table.Thead>
        <Table.Tr>
          <Table.Th>Started</Table.Th>
          <Table.Th>Outcome</Table.Th>
          <Table.Th>Summary</Table.Th>
          <Table.Th>By</Table.Th>
          <Table.Th />
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {applies.map((a) => {
          const words = applyOutcomeWords(a.outcome);
          return (
            <Table.Tr key={a.id}>
              <Table.Td className={classes.compare}>{absoluteLabel(a.startedAt)}</Table.Td>
              <Table.Td>
                <Text size="xs" className={classes.state} data-tone={words.tone}>
                  {words.text}
                </Text>
              </Table.Td>
              <Table.Td>{a.summary ?? ''}</Table.Td>
              <Table.Td>{a.actor}</Table.Td>
              <Table.Td className={classes.actionCell}>
                <Group gap="xs" justify="flex-end" wrap="nowrap">
                  {a.auditEventId != null ? (
                    <Anchor component={Link} to={`/clusters/${clusterId}/audit?action=APPLY_BROKER_CONFIG`} size="xs">
                      audit
                    </Anchor>
                  ) : null}
                  <Button
                    variant="subtle"
                    size="compact-xs"
                    onClick={() => onToggle(a.id)}
                    aria-expanded={openApply === a.id}
                  >
                    {openApply === a.id ? 'Hide' : 'Details'}
                  </Button>
                </Group>
              </Table.Td>
            </Table.Tr>
          );
        })}
      </Table.Tbody>
    </Table>
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
  useDisplayZone();
  const revisions = useBrokerConfigRevisions(declaration.clusterId);
  const applies = useBrokerConfigApplies(declaration.clusterId);
  const [compare, setCompare] = useState<ConfigRevisionView | null>(null);
  const [openApply, setOpenApply] = useState<number | null>(null);
  const detail = useBrokerConfigApply(declaration.clusterId, openApply);

  const current = revisions.data?.find((r) => r.revision === declaration.revision);
  const diff = compare && current ? diffDocuments(compare.document, current.document, catalogue) : [];

  return (
    <Stack gap="lg">
      <Stack gap="xs">
        <Text size="sm" fw={600}>
          Revisions
        </Text>
        {revisions.isPending ? (
          <Skeleton height={60} />
        ) : (
          <Table fz="xs" verticalSpacing={4}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Revision</Table.Th>
                <Table.Th>Saved</Table.Th>
                <Table.Th>By</Table.Th>
                <Table.Th>Source</Table.Th>
                <Table.Th>Note</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {(revisions.data ?? []).map((r) => (
                <Table.Tr key={r.revision}>
                  <Table.Td className={classes.compare}>{r.revision}</Table.Td>
                  <Table.Td className={classes.compare}>{absoluteLabel(r.createdAt)}</Table.Td>
                  <Table.Td>{r.createdBy}</Table.Td>
                  <Table.Td>{r.source.toLowerCase().replaceAll('_', ' ')}</Table.Td>
                  <Table.Td>{r.note ?? ''}</Table.Td>
                  <Table.Td className={classes.actionCell}>
                    {r.revision !== declaration.revision ? (
                      <Button
                        variant="subtle"
                        size="compact-xs"
                        onClick={() => setCompare((c) => (c?.revision === r.revision ? null : r))}
                        aria-expanded={compare?.revision === r.revision}
                      >
                        {compare?.revision === r.revision ? 'Hide comparison' : 'Compare with current'}
                      </Button>
                    ) : (
                      <Text size="xs" c="dimmed">
                        current
                      </Text>
                    )}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
        {compare ? <CompareDiff diff={diff} compare={compare} current={declaration.revision} /> : null}
      </Stack>

      <Stack gap="xs">
        <Text size="sm" fw={600}>
          Applies
        </Text>
        {appliesNotice(applies) ?? (
          <AppliesTable
            applies={applies.data ?? []}
            clusterId={declaration.clusterId}
            openApply={openApply}
            onToggle={(id) => setOpenApply((o) => (o === id ? null : id))}
          />
        )}
        {openApply !== null && detail.data ? (
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
      </Stack>
    </Stack>
  );
}
