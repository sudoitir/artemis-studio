import { Divider } from '@mantine/core';

import { NAV_ITEMS } from './navItems.ts';
import { NavItem } from './NavItem.tsx';

/**
 * The per-cluster section nav (moved out of `ClusterLayout`'s horizontal view
 * strip — ADR-0034; that strip already overflowed at 12 items). Only rendered
 * while a cluster is the active route.
 */
export function ClusterViewNav({ clusterId, collapsed }: { clusterId: string; collapsed: boolean }) {
  return (
    <>
      <Divider my="xs" />
      <nav aria-label="Cluster views">
        {NAV_ITEMS.map((item) => (
          <NavItem
            key={item.path}
            to={`/clusters/${clusterId}/${item.path}`}
            label={item.label}
            collapsed={collapsed}
            leading={<item.icon size={18} stroke={1.5} />}
          />
        ))}
      </nav>
    </>
  );
}
