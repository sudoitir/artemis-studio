import { useFiringAlerts } from './api.ts';
import styles from './FiringNodeMark.module.css';

const NODE_SUBJECT_PREFIX = 'node:';

/** A dot on a topology box while an alert on one of its nodes is firing (`topology.node.marks`). */
export function FiringNodeMark({ clusterId, nodeIds }: { clusterId: string; nodeIds: string[] }) {
  const firing = useFiringAlerts(clusterId);
  const on = (firing.data ?? []).some(
    (f) => f.subjectKey.startsWith(NODE_SUBJECT_PREFIX) && nodeIds.includes(f.subjectKey.slice(NODE_SUBJECT_PREFIX.length)),
  );
  return on ? (
    <span className={styles.dot} role="img" aria-label="Alert firing" title="An alert is firing on this node" />
  ) : null;
}
