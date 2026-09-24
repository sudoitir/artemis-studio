import { IconClipboard, IconTrash } from '@tabler/icons-react';

import type { ActionProps, DivertTarget } from '../../kernel/actions/types.ts';
import { ActionMenuItem } from '../../ui/ActionMenuItem.tsx';
import { DeleteDivertDialog } from './DivertActions.tsx';
import { useDivertWriteGate } from './divertGate.ts';

export function CopyDivertName({ target, host }: ActionProps<DivertTarget>) {
  return (
    <ActionMenuItem
      label="Copy divert name"
      icon={<IconClipboard size={16} aria-hidden />}
      onSelect={() => host.copy(target.name, 'divert name')}
    />
  );
}

/**
 * "Delete divert…" on a divert's row. A capture divert is Studio's own and reconciliation would put
 * it back, so it is not deletable here, and the item says where it is managed instead.
 */
export function DeleteDivert({ clusterId, target, host }: ActionProps<DivertTarget>) {
  const gate = useDivertWriteGate(clusterId);
  const divert = target.snapshot;
  const verdict =
    divert?.owner === 'MESSAGE_CAPTURE'
      ? ({
          kind: 'blocked',
          reason:
            'This divert belongs to a message capture subscription. Delete the subscription in Settings → Message index; deleting the divert here would be undone on its next reconciliation.',
        } as const)
      : gate;
  return (
    <ActionMenuItem
      label="Delete divert…"
      icon={<IconTrash size={16} aria-hidden />}
      tone="danger"
      verdict={verdict}
      onExplain={(v) => host.explain(v, 'deleting this divert')}
      onSelect={() => divert && host.open(DeleteDivertDialog, { clusterId, divert })}
    />
  );
}
