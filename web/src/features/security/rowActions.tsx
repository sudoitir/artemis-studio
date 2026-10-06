import { IconUsersGroup } from '@tabler/icons-react';

import type { ActionProps, AddressTarget } from '../../kernel/actions/types.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import { ActionMenuItem } from '../../ui/ActionMenuItem.tsx';
import { AddressAccessDrawer } from './ResourceAccessPanel.tsx';

/** "Who has access…" on an address's row: its owner team, and every team and role that may act on it. */
export function AddressAccess({ clusterId, target, host }: Readonly<ActionProps<AddressTarget>>) {
  const { can } = useCan();
  // Only for those who can act on a request for access; for anyone else it is not theirs to be offered.
  if (!can('user:admin') && !can('team:admin')) return null;
  return (
    <ActionMenuItem
      label="Who has access…"
      icon={<IconUsersGroup size={16} aria-hidden />}
      onSelect={() => host.open(AddressAccessDrawer, { clusterId, address: target.address })}
    />
  );
}
