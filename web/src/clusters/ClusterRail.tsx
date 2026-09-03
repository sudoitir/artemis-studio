import type { ClusterSummary } from '../api/client.ts';
import { RegisterClusterButton } from './RegisterCluster.tsx';
import styles from './ClusterRail.module.css';

/**
 * The cluster list. A small health mark leads each row — neutral when healthy,
 * coloured only when something is wrong — and the current selection is carried
 * by weight and an inline-start rule, not colour.
 */
export function ClusterRail({
  clusters,
  selectedId,
  onSelect,
}: {
  clusters: ClusterSummary[];
  selectedId: string | null;
  onSelect: (id: string) => void;
}) {
  return (
    <nav className={styles.rail} aria-label="Clusters">
      {clusters.map((c) => (
        <button
          key={c.id}
          type="button"
          className={styles.item}
          data-active={c.id === selectedId || undefined}
          aria-current={c.id === selectedId ? 'true' : undefined}
          onClick={() => onSelect(c.id)}
        >
          <span
            className={styles.mark}
            data-health={c.health}
            aria-hidden="true"
          />
          <span className={styles.name}>{c.name}</span>
          <span className={styles.count}>{c.nodeCount}</span>
        </button>
      ))}
      <div className={styles.footer}>
        <RegisterClusterButton />
      </div>
    </nav>
  );
}
