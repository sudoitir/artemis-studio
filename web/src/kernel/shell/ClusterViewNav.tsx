import { Divider, Text, VisuallyHidden } from '@mantine/core';

import { useCan } from '../auth/useCan.ts';
import { useFeatures } from '../features.ts';
import { navGroups } from '../registry.ts';
import styles from './ClusterViewNav.module.css';
import { NavItem } from './NavItem.tsx';

/**
 * The per-cluster view nav (ADR-0034), grouped (ADR-0070). Each enabled feature contributes its
 * views to one of the kernel's groups; a group with no enabled view is not shown. Expanded, a group
 * has a visible heading; collapsed, a divider, and its heading stays available to a screen reader.
 * A view the operator lacks the read permission for stays listed and disabled, with the reason:
 * a missing entry would read as a product that cannot do it. Only rendered while a cluster is the
 * active route.
 */
export function ClusterViewNav({ clusterId, collapsed }: { clusterId: string; collapsed: boolean }) {
  const groups = navGroups(useFeatures());
  // While grants are still loading the entries are offered: refusing before the answer arrives
  // would claim something that has not been checked.
  const { can, loading } = useCan();

  return (
    <>
      <Divider my="xs" />
      <nav aria-label="Cluster views">
        {groups.map((group) => {
          const headingId = `cluster-views-${group.id}`;
          const heading = (
            <Text id={headingId} component="h2" className={styles.heading}>
              {group.label}
            </Text>
          );
          return (
            <div key={group.id} role="group" aria-labelledby={headingId} className={styles.group}>
              {collapsed ? (
                <>
                  <Divider my={6} />
                  <VisuallyHidden>{heading}</VisuallyHidden>
                </>
              ) : (
                heading
              )}
              {group.items.map((item) => (
                <NavItem
                  key={item.path}
                  to={`/clusters/${clusterId}/${item.path}`}
                  label={item.label}
                  collapsed={collapsed}
                  leading={<item.icon size={18} stroke={1.5} />}
                  trailing={item.Badge ? <item.Badge clusterId={clusterId} /> : undefined}
                  disabledReason={
                    item.permission && !loading && !can(item.permission, clusterId)
                      ? `Opening ${item.label} needs the ${item.permission} permission on this cluster.`
                      : undefined
                  }
                />
              ))}
            </div>
          );
        })}
      </nav>
    </>
  );
}
