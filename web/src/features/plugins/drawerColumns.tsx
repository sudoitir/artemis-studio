import type { AuditEventView, PluginPurgePlanView } from './api.ts';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { Column } from '../../ui/table/index.ts';
import { absoluteLabel } from '../../kernel/time/time.ts';

type PurgeTable = PluginPurgePlanView['tables'][number];

export function bytes(n: number): string {
  if (n >= 1024 * 1024 * 1024) return `${(n / 1024 / 1024 / 1024).toFixed(1)} GB`;
  if (n >= 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`;
  return `${Math.ceil(n / 1024)} KB`;
}

const rowsText = (t: PurgeTable) => (t.estimatedRows < 0 ? 'not yet counted' : t.estimatedRows.toLocaleString());

/** The tables a plugin keeps: their name, and their size as table statistics estimate it. */
export function dataColumns(): Column<PurgeTable>[] {
  return [
    { id: 'table', header: 'Table', accessor: (t) => t.name, kind: 'identifier', priority: 'essential', wrap: true },
    {
      id: 'rows',
      header: 'Rows (about)',
      accessor: rowsText,
      kind: 'number',
      priority: 'essential',
      description: 'An estimate from table statistics',
    },
    { id: 'size', header: 'Size', accessor: (t) => bytes(t.bytes), kind: 'number', priority: 'essential' },
  ];
}

/** What was done to a plugin: "PLUGIN_UNINSTALL_FAILED" reads "uninstall failed". */
const actionWords = (e: AuditEventView) =>
  e.action
    .replace(/^PLUGIN_/, '')
    .replaceAll('_', ' ')
    .toLowerCase();

const outcomeWords = (e: AuditEventView) => e.outcome.toLowerCase();

/** Everything done to a plugin: when, who, what, how it ended in words, and the reason when it did not. */
export function historyColumns(): Column<AuditEventView>[] {
  return [
    {
      id: 'when',
      header: 'When',
      accessor: (e) => absoluteLabel(e.ts),
      kind: 'time',
      priority: 'essential',
    },
    {
      id: 'who',
      header: 'Who',
      accessor: (e) => e.username ?? '—',
      kind: 'identifier',
      priority: 'essential',
      wrap: true,
    },
    { id: 'what', header: 'What', accessor: actionWords, kind: 'text', priority: 'essential' },
    {
      id: 'outcome',
      header: 'Outcome',
      accessor: outcomeWords,
      cell: (e) => <StatusBadge tone={e.outcome === 'FAILED' ? 'danger' : 'neutral'}>{outcomeWords(e)}</StatusBadge>,
      kind: 'status',
      priority: 'essential',
      badge: true,
    },
    { id: 'detail', header: 'Detail', accessor: (e) => e.error ?? '', kind: 'text', priority: 'high', wrap: true },
  ];
}
