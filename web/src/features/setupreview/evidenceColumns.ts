import type { Column } from '../../ui/table/index.ts';
import type { SetupFindingView } from './api.ts';

type Evidence = SetupFindingView['evidence'][number];

/**
 * What each node reported for a finding, key by key. The node identifies a row; the key and the value
 * wrap, so a long setting never widens the card.
 */
export function evidenceColumns(): Column<Evidence>[] {
  return [
    {
      id: 'node',
      header: 'Node',
      accessor: (e) => e.node ?? 'cluster',
      kind: 'identifier',
      wrap: true,
      priority: 'essential',
    },
    { id: 'key', header: 'Reported', accessor: (e) => e.key, kind: 'text', wrap: true, priority: 'essential' },
    { id: 'value', header: 'Value', accessor: (e) => e.value ?? '—', kind: 'code', wrap: true, priority: 'essential' },
  ];
}
