import type { Column } from '../../ui/table/index.ts';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { NodeProbeView } from './api.ts';
import { accountResultWords, managementUrlWords } from './connectionWords.ts';

/** One account's result as a badge, in words; a refusal is the one that draws the eye. */
function accountBadge(result: NodeProbeView['management']) {
  const tone = result === 'REJECTED' || result === 'UNREACHABLE' ? 'warning' : 'neutral';
  return <StatusBadge tone={tone}>{accountResultWords(result)}</StatusBadge>;
}

/** Why a passive backup is not asked for its Core account. */
function coreWords(node: NodeProbeView): string {
  if (node.core === 'NOT_TRIED' && node.haRole === 'BACKUP')
    return 'Not tried: a passive backup takes no Core connection';
  return accountResultWords(node.core);
}

/**
 * What a connection check found, one row per node: who it is, where Studio manages it, and what each of
 * the two accounts did there. The node identifies a row and the two accounts are never hidden; the rest
 * go first when the window is narrow.
 */
export const NODE_PROBE_COLUMNS: Column<NodeProbeView>[] = [
  { id: 'node', header: 'Node', accessor: (n) => n.name, kind: 'identifier', priority: 'essential' },
  { id: 'role', header: 'Role', accessor: (n) => n.haRole, kind: 'status', priority: 'high' },
  {
    id: 'management',
    header: 'Management account',
    short: 'Management',
    accessor: (n) => accountResultWords(n.management),
    cell: (n) => accountBadge(n.management),
    kind: 'status',
    badge: true,
    priority: 'essential',
  },
  {
    id: 'core',
    header: 'Core account',
    short: 'Core',
    accessor: coreWords,
    cell: (n) => accountBadge(n.core),
    kind: 'status',
    badge: true,
    priority: 'essential',
  },
  {
    id: 'url',
    header: 'Management URL',
    accessor: managementUrlWords,
    kind: 'code',
    priority: 'high',
    wrap: true,
  },
  { id: 'version', header: 'Version', accessor: (n) => n.version ?? 'Unknown', kind: 'status', priority: 'low' },
  {
    id: 'nodeId',
    header: 'Node ID',
    accessor: (n) => n.artemisNodeId ?? 'Not reported',
    kind: 'identifier',
    priority: 'low',
  },
];
