import { Text } from '@mantine/core';

import { EmptyState } from '../../ui/EmptyState.tsx';
import { DataTable } from '../../ui/table/index.ts';
import type { NodeProbeView } from './api.ts';
import { NODE_PROBE_COLUMNS } from './nodeProbeColumns.tsx';

/**
 * The per-node result of a connection check, the same table for a registration and for an edit of a
 * registered cluster's connection, so what was checked reads the same in both.
 */
export function NodeProbeTable({ nodes, label }: Readonly<{ nodes: NodeProbeView[]; label: string }>) {
  return (
    <>
      <DataTable
        variant="static"
        label={label}
        storageKey="clusters.probe"
        columns={NODE_PROBE_COLUMNS}
        data={nodes}
        rowKey={(n) => n.name}
        height={{ maxRows: Math.max(nodes.length, 1) }}
        empty={<EmptyState kind="empty" title="No node was found" description="The check reached no broker." />}
      />
      {nodes.some((n) => n.core === 'NOT_TRIED' && n.haRole === 'BACKUP') ? (
        <Text size="xs" c="dimmed">
          A passive backup opens no Core acceptor, so its Core account is not asked. It is tried once the backup takes
          over.
        </Text>
      ) : null}
    </>
  );
}
