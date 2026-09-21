import { useState } from 'react';
import { Button } from '@mantine/core';

import { useCluster } from '../clusters/index.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import type { SlotProps } from '../../kernel/slots.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { gateFor } from '../../ui/capabilityGate.ts';
import type { BulkOperation } from './api.ts';
import { BulkPreviewDialog } from './BulkPreviewDialog.tsx';
import { OPERATIONS } from './words.ts';

const ORDER: BulkOperation[] = ['PAUSE', 'RESUME', 'PURGE', 'DELETE'];

/**
 * The bulk actions over the queues screen's selection (`queues.selection`). Each is gated on the
 * permission of the single-queue command it applies, and opens its preview; nothing acts from here.
 */
export function BulkActionBar({ clusterId, selection, clear }: SlotProps['queues.selection']) {
  const { can, loading } = useCan();
  const cluster = useCluster(clusterId);
  const write = cluster.data?.capabilities.managementWrite;
  // The dialog stays mounted and is opened and closed, so the modal hands focus back to the button
  // that opened it.
  const [operation, setOperation] = useState<BulkOperation>('PAUSE');
  const [opened, setOpened] = useState(false);

  return (
    <>
      {ORDER.map((each) => {
        const op = OPERATIONS[each];
        const gate = gateFor(can(op.permission, clusterId), op.permissionLabel, write, loading || cluster.isPending);
        return (
          <CapabilityGate key={each} verdict={gate} what={`${op.gerund} these queues`}>
            <Button
              size="xs"
              variant="light"
              color={op.destructive ? 'red' : undefined}
              disabled={gate.kind === 'blocked'}
              onClick={() => {
                setOperation(each);
                setOpened(true);
              }}
            >
              {op.destructive ? `${op.verb}…` : op.verb}
            </Button>
          </CapabilityGate>
        );
      })}
      <BulkPreviewDialog
        clusterId={clusterId}
        operation={operation}
        selection={selection}
        opened={opened}
        onClose={() => setOpened(false)}
        onStarted={clear}
      />
    </>
  );
}
