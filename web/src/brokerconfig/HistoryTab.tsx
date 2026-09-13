import { useState } from 'react';
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
} from '../api/client.ts';
import { absoluteLabel } from '../app/time.ts';
import { useDisplayZone } from '../app/timezone.ts';
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

function diffDocuments(a: ConfigDocumentView, b: ConfigDocumentView, catalogue?: ConfigCatalogueView): DiffRow[] {
  const left = documentItems(a, catalogue);
  const right = documentItems(b, catalogue);
  const out: DiffRow[] = [];
  for (const item of [...new Set([...left.keys(), ...right.keys()])].sort()) {
    const l = left.get(item);
    const r = right.get(item);
    if (!l) {
      out.push({ item, key: '', left: 'not declared', right: `declared (${r!.length} ${r!.length === 1 ? 'key' : 'keys'})` });
      continue;
    }
    if (!r) {
      out.push({ item, key: '', left: `declared (${l.length} ${l.length === 1 ? 'key' : 'keys'})`, right: 'not declared' });
      continue;
    }
    const lm = new Map(l.map((x) => [x.key, x.value]));
    const rm = new Map(r.map((x) => [x.key, x.value]));
    for (const key of [...new Set([...lm.keys(), ...rm.keys()])].sort()) {
      if (lm.get(key) !== rm.get(key)) {
        out.push({ item, key, left: lm.get(key) ?? 'not declared', right: rm.get(key) ?? 'not declared' });
      }
    }
  }
  return out;
}

/** Revisions and applies: who, when, what, with a link into the audit log for every real apply. */
export function HistoryTab({
  declaration,
  catalogue,
}: {
  declaration: ConfigDeclarationView;
  catalogue?: ConfigCatalogueView;
}) {
  useDisplayZone();
  const revisions = useBrokerConfigRevisions(declaration.clusterId);
  const applies = useBrokerConfigApplies(declaration.clusterId);
  const [compare, setCompare] = useState<ConfigRevisionView | null>(null);
  const [openApply, setOpenApply] = useState<number | null>(null);
  const detail = useBrokerConfigApply(declaration.clusterId, openApply);

  const current = revisions.data?.find((r) => r.revision === declaration.revision);
  const diff = compare && current ? diffDocuments(compare.document, current.document, catalogue) : [];
  const changedItems = new Set(diff.map((d) => d.item)).size;

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
                  <Table.Td>{r.source.toLowerCase().replace(/_/g, ' ')}</Table.Td>
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
        {compare ? (
          <Stack gap={4}>
            <Text size="xs" c="dimmed">
              {diff.length === 0
                ? `Revision ${compare.revision} and revision ${declaration.revision} declare the same thing.`
                : `${changedItems} item${changedItems === 1 ? '' : 's'} differ between revision ${compare.revision} and revision ${declaration.revision}.`}
            </Text>
            {diff.length > 0 ? (
              <Table fz="xs" verticalSpacing={4} withTableBorder>
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>Item</Table.Th>
                    <Table.Th>Key</Table.Th>
                    <Table.Th>Revision {compare.revision}</Table.Th>
                    <Table.Th>Revision {declaration.revision}</Table.Th>
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
        ) : null}
      </Stack>

      <Stack gap="xs">
        <Text size="sm" fw={600}>
          Applies
        </Text>
        {applies.isPending ? (
          <Skeleton height={60} />
        ) : (applies.data ?? []).length === 0 ? (
          <Text size="xs" c="dimmed">
            Nothing has been applied or previewed yet.
          </Text>
        ) : (
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
              {(applies.data ?? []).map((a) => {
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
                          <Anchor
                            component={Link}
                            to={`/clusters/${declaration.clusterId}/audit?action=APPLY_BROKER_CONFIG`}
                            size="xs"
                          >
                            audit
                          </Anchor>
                        ) : null}
                        <Button
                          variant="subtle"
                          size="compact-xs"
                          onClick={() => setOpenApply((o) => (o === a.id ? null : a.id))}
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
