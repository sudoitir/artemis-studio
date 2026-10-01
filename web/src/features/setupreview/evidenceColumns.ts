import type { Column } from '../../ui/table/index.ts';
import type { SetupFindingView } from './api.ts';

type Evidence = SetupFindingView['evidence'][number];

/**
 * What each node reported for a finding, key by key. The node and the key identify a row and are shortened
 * in the middle; the value wraps, so a long setting never widens the card.
 */
export function evidenceColumns(): Column<Evidence>[] {
  return [
    {
      id: 'node',
      header: 'Node',
      accessor: (e) => e.node ?? 'cluster',
      kind: 'identifier',
      priority: 'essential',
    },
    { id: 'key', header: 'Reported', accessor: (e) => e.key, kind: 'identifier', priority: 'essential' },
    { id: 'value', header: 'Value', accessor: (e) => e.value ?? '—', kind: 'code', wrap: true, priority: 'essential' },
  ];
}
