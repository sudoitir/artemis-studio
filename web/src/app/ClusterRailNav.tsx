import { Loader } from '@mantine/core';

import { useClusters } from '../api/client.ts';
import { RegisterClusterButton } from '../clusters/RegisterCluster.tsx';
import { NavItem } from './NavItem.tsx';
import styles from '../clusters/ClusterRail.module.css';

/**
 * The cluster list: each row is a link to that cluster's topology view. A small
 * health mark leads the row — neutral when healthy, coloured only when
 * something is wrong — and the active row is carried by weight and an
 * inline-start rule, not colour (non-negotiable #6). Collapsed rows keep the
 * health mark and add a two-letter monogram, since the mark alone doesn't say
 * *which* cluster — the whole point of a glanceable rail.
 */
export function ClusterRailNav({ collapsed }: { collapsed: boolean }) {
  const clusters = useClusters();

  if (!clusters.data) {
    return <Loader size="sm" />;
  }

  return (
    <nav aria-label="Clusters">
      {clusters.data.map((c) => (
        <NavItem
          key={c.id}
          to={`/clusters/${c.id}`}
          label={c.name}
          collapsed={collapsed}
          trailing={c.nodeCount}
          leading={
            collapsed ? (
              <span className={styles.monogram} data-health={c.health} aria-hidden="true">
                {c.name.slice(0, 2).toUpperCase()}
              </span>
            ) : (
              <span className={styles.mark} data-health={c.health} aria-hidden="true" />
            )
          }
        />
      ))}
      <div className={styles.footer}>
        <RegisterClusterButton />
      </div>
    </nav>
  );
}
