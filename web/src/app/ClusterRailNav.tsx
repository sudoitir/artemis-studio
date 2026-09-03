import { Loader } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import { useClusters } from '../api/client.ts';
import { RegisterClusterButton } from '../clusters/RegisterCluster.tsx';
import styles from '../clusters/ClusterRail.module.css';

/**
 * The cluster list, now URL-driven: each row is a link to that cluster's
 * topology view. A small health mark leads the row — neutral when healthy,
 * coloured only when something is wrong — and the active row is carried by
 * weight and an inline-start rule, not colour (non-negotiable #6).
 */
export function ClusterRailNav() {
  const clusters = useClusters();

  if (!clusters.data) {
    return <Loader size="sm" />;
  }

  return (
    <nav className={styles.rail} aria-label="Clusters">
      {clusters.data.map((c) => (
        <Link
          key={c.id}
          to={`/clusters/${c.id}`}
          className={styles.item}
          activeOptions={{ exact: false }}
          activeProps={{ 'data-active': 'true', 'aria-current': 'page' }}
        >
          <span className={styles.mark} data-health={c.health} aria-hidden="true" />
          <span className={styles.name}>{c.name}</span>
          <span className={styles.count}>{c.nodeCount}</span>
        </Link>
      ))}
      <div className={styles.footer}>
        <RegisterClusterButton />
      </div>
    </nav>
  );
}
