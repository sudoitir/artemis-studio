import { Button, Group, Text } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import type { ConfigApplyHistoryView, ConfigCatalogueView, ConfigDocumentView, ConfigRevisionView } from './api.ts';
import { absoluteLabel } from '../../kernel/time/time.ts';
import { AUTO, localZone } from '../../kernel/time/timezone.ts';
import linkClasses from '../../ui/InlineLink.module.css';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { Column } from '../../ui/table/index.ts';
import { documentItems } from './pretty.ts';
import { applyOutcomeWords } from './words.ts';

const zoneName = (zone: string) => (zone === AUTO ? localZone() : zone);

const sourceWords = (source: string) => source.toLowerCase().replaceAll('_', ' ');

/**
 * The revisions table. The revision number identifies a row; each earlier revision offers its
 * comparison with the current one, which is a statement, not a button, on the current revision.
 */
export function revisionColumns(
  zone: string,
  current: number,
  comparing: number | undefined,
  onCompare: (revision: ConfigRevisionView) => void,
): Column<ConfigRevisionView>[] {
  return [
    { id: 'revision', header: 'Revision', accessor: (r) => r.revision, kind: 'number', priority: 'essential' },
    {
      id: 'saved',
      header: 'Saved',
      accessor: (r) => absoluteLabel(r.createdAt),
      description: `When the revision was saved, in ${zoneName(zone)}`,
      kind: 'time',
      priority: 'essential',
    },
    { id: 'by', header: 'By', accessor: (r) => r.createdBy, kind: 'identifier', priority: 'essential' },
    { id: 'source', header: 'Source', accessor: (r) => sourceWords(r.source), kind: 'text', priority: 'high' },
    { id: 'note', header: 'Note', accessor: (r) => r.note ?? '', kind: 'text', priority: 'high', wrap: true },
    {
      id: 'compare',
      header: 'Compare',
      accessor: (r) => (r.revision === current ? 'current' : 'Compare with current'),
      cell: (r) =>
        r.revision === current ? (
          <Text size="sm" c="dimmed">
            current
          </Text>
        ) : (
          <Button
            variant="subtle"
            size="compact-sm"
            onClick={() => onCompare(r)}
            aria-expanded={comparing === r.revision}
          >
            {comparing === r.revision ? 'Hide comparison' : 'Compare with current'}
          </Button>
        ),
      kind: 'status',
      priority: 'essential',
      wrap: true,
    },
  ];
}

/** The applies table: when, how it ended in words, what it said, who ran it, and its audit event. */
export function applyColumns(
  zone: string,
  clusterId: string,
  openApply: number | null,
  onToggle: (id: number) => void,
): Column<ConfigApplyHistoryView>[] {
  return [
    {
      id: 'started',
      header: 'Started',
      accessor: (a) => absoluteLabel(a.startedAt),
      description: `When the apply started, in ${zoneName(zone)}`,
      kind: 'time',
      priority: 'essential',
    },
    {
      id: 'outcome',
      header: 'Outcome',
      accessor: (a) => applyOutcomeWords(a.outcome).text,
      cell: (a) => {
        const words = applyOutcomeWords(a.outcome);
        return <StatusBadge tone={words.tone ?? 'neutral'}>{words.text}</StatusBadge>;
      },
      kind: 'status',
      badge: true,
      priority: 'essential',
    },
    { id: 'summary', header: 'Summary', accessor: (a) => a.summary ?? '', kind: 'text', priority: 'high', wrap: true },
    { id: 'by', header: 'By', accessor: (a) => a.actor, kind: 'identifier', priority: 'high' },
    {
      id: 'actions',
      header: 'Details',
      accessor: (a) => (a.auditEventId == null ? 'Details' : 'audit Details'),
      cell: (a) => (
        <Group gap="xs" justify="flex-end" wrap="wrap">
          {a.auditEventId == null ? null : (
            <Link to={`/clusters/${clusterId}/audit?action=APPLY_BROKER_CONFIG`} className={linkClasses.link}>
              audit
            </Link>
          )}
          <Button variant="subtle" size="compact-sm" onClick={() => onToggle(a.id)} aria-expanded={openApply === a.id}>
            {openApply === a.id ? 'Hide' : 'Details'}
          </Button>
        </Group>
      ),
      kind: 'status',
      priority: 'essential',
      min: 14,
      wrap: true,
    },
  ];
}

/**
 * What changed between two documents, item by item and key by key: an item
 * added or removed is one row stating so; an item present in both lists only
 * the keys whose value differs, so the operator reads the change, not the JSON.
 */
export interface DiffRow {
  id: string;
  item: string;
  key: string;
  left: string;
  right: string;
  /** Whether it is the first row of its item: the item is named once per group. */
  first: boolean;
}

type Change = Omit<DiffRow, 'id' | 'first'>;

const declaredLabel = (keys: number) => `declared (${keys} ${keys === 1 ? 'key' : 'keys'})`;

/** An item declared on one side only is one row stating so. */
function addedOrRemoved(item: string, l: unknown[] | undefined, r: unknown[] | undefined): Change {
  if (!l) return { item, key: '', left: 'not declared', right: declaredLabel(r!.length) };
  return { item, key: '', left: declaredLabel(l.length), right: 'not declared' };
}

export function diffDocuments(
  a: ConfigDocumentView,
  b: ConfigDocumentView,
  catalogue?: ConfigCatalogueView,
): DiffRow[] {
  const left = documentItems(a, catalogue);
  const right = documentItems(b, catalogue);
  const out: Change[] = [];
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
  return out.map((change, i) => ({
    ...change,
    id: `${change.item}/${change.key}`,
    first: i === 0 || out[i - 1].item !== change.item,
  }));
}

/** The comparison's columns: the item (named once per group), the key, and both revisions' values. */
export function diffColumns(compared: number, current: number): Column<DiffRow>[] {
  return [
    {
      id: 'item',
      header: 'Item',
      accessor: (d) => (d.first ? d.item : ''),
      kind: 'identifier',
      priority: 'essential',
      wrap: true,
    },
    { id: 'key', header: 'Key', accessor: (d) => d.key, kind: 'code', priority: 'essential', wrap: true },
    {
      id: 'left',
      header: `Revision ${compared}`,
      accessor: (d) => d.left,
      kind: 'text',
      priority: 'essential',
      wrap: true,
    },
    {
      id: 'right',
      header: `Revision ${current}`,
      accessor: (d) => d.right,
      kind: 'text',
      priority: 'essential',
      wrap: true,
    },
  ];
}
