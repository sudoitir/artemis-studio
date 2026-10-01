import { createElement } from 'react';

import { absoluteLabel } from '../../kernel/time/time.ts';
import { AUTO, localZone } from '../../kernel/time/timezone.ts';
import type { Column } from '../../ui/table/index.ts';
import type { StoreView, TableView } from './api.ts';
import { TableState } from './cells.tsx';
import { bytes, count, quotaWords, retentionWords } from './words.ts';

/** One table's storage health, one row of the health grid. */
type HealthRow = TableView;

function growth(t: HealthRow): string {
  if (t.growthBytes == null) return 'unknown';
  return `${t.growthBytes >= 0 ? '+' : ''}${bytes(t.growthBytes)}`;
}

function partitions(t: HealthRow): string {
  if (!t.partitioned) return 'n/a';
  return t.missingPartitions.length ? `missing ${t.missingPartitions.join(', ')}` : 'ready';
}

/**
 * The storage health grid's columns. The table's name identifies a row and is never hidden; its state,
 * size and growth go next, and the vacuum and partition detail are the first to be hidden when the
 * table is narrow. The vacuum time is written in the display zone `zone`, which the header states, so a
 * view builds the columns again when the zone changes.
 */
export function healthColumns(zone: string): Column<HealthRow>[] {
  return [
    {
      id: 'table',
      header: 'Table',
      accessor: (t) => (t.schema === 'public' ? t.name : `${t.schema}.${t.name}`),
      kind: 'identifier',
      priority: 'essential',
    },
    {
      id: 'state',
      header: 'State',
      accessor: (t) => (t.problems.length ? `Unhealthy: ${t.problems.join('; ')}` : 'Healthy'),
      cell: (t) => createElement(TableState, { table: t }),
      kind: 'text',
      badge: true,
      priority: 'high',
    },
    { id: 'size', header: 'Size', accessor: (t) => bytes(t.bytes), kind: 'number', priority: 'high' },
    { id: 'growth', header: '7-day growth', accessor: growth, kind: 'number', priority: 'high' },
    {
      id: 'dead',
      header: 'Dead rows',
      accessor: (t) => `${t.deadPercent}% of ${count(t.rows + t.deadRows)}`,
      kind: 'number',
      priority: 'low',
    },
    {
      id: 'vacuum',
      header: 'Last vacuum',
      accessor: (t) => (t.lastVacuum ? absoluteLabel(t.lastVacuum) : 'never'),
      description: `When Postgres last vacuumed the table, in ${zone === AUTO ? localZone() : zone}`,
      kind: 'time',
      priority: 'low',
    },
    { id: 'partitions', header: 'Partitions', accessor: partitions, kind: 'status', priority: 'low' },
  ];
}

function lastPurge(s: StoreView): string {
  if (s.lastPurgeAt == null) return s.retention === 'forever' ? 'Never: kept forever' : 'Not yet';
  const at = absoluteLabel(s.lastPurgeAt);
  return s.lastPurgeError ? `${at}: failed, ${s.lastPurgeError}` : `${at}: ${count(s.lastPurged)} rows`;
}

/**
 * The retention grid's columns. The store identifies a row and is never hidden, and says beside its name
 * when it is over its quota warning; its retention, usage and last purge, where a failure shows, go
 * next, and the quota is the first to be hidden when the table is narrow. The purge time is written in
 * the display zone `zone`, which the header states, so a view builds the columns again when it changes.
 */
export function storeColumns(zone: string): Column<StoreView>[] {
  return [
    {
      id: 'store',
      header: 'Store',
      accessor: (s) =>
        [s.label, s.source !== 'core' ? `from ${s.source}` : null, s.overWarning ? 'over its quota warning' : null]
          .filter(Boolean)
          .join(' · '),
      kind: 'text',
      priority: 'essential',
    },
    {
      id: 'retention',
      header: 'Retention',
      accessor: (s) => retentionWords(s.retention),
      kind: 'status',
      priority: 'high',
    },
    {
      id: 'rows',
      header: 'Rows',
      accessor: (s) => (s.usageError ? 'unreadable' : count(s.rows)),
      kind: 'number',
      priority: 'high',
    },
    {
      id: 'size',
      header: 'Size',
      accessor: (s) => (s.usageError ? 'unreadable' : bytes(s.bytes)),
      kind: 'number',
      priority: 'high',
    },
    { id: 'quota', header: 'Quota', accessor: quotaWords, kind: 'status', priority: 'low' },
    {
      id: 'purge',
      header: 'Last purge',
      accessor: lastPurge,
      description: `When the nightly housekeeping run last purged the store, in ${zone === AUTO ? localZone() : zone}`,
      kind: 'text',
      priority: 'high',
    },
  ];
}
