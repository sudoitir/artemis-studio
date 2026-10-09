import { Outlet, useParams } from '@tanstack/react-router';

import { ActionHostProvider } from '../actions/ActionHost.tsx';
import { useFeatures } from '../features.ts';
import { useSlot } from '../slots.ts';
import { useClusterStream } from '../stream/useClusterStream.ts';
import { Breadcrumb } from './Breadcrumb.tsx';
import styles from './ClusterLayout.module.css';

/**
 * One cluster's screen: one context line, then the routed view, whose own `PageHeader` is the page's
 * one h1 (ADR-0163). The line is the breadcrumb at its start and, at its end, whatever a
 * `cluster.header` contribution marks with `data-context-line` (the clusters feature's environment,
 * nodes and health); every other part of a contribution, such as a health notice, takes a row of its
 * own below the line. Nothing in it is a heading.
 *
 * It hosts the dialogs row actions open (ADR-0107), and mounts the cluster's one SSE stream, subscribed
 * to the topics of every enabled feature (ADR-0018, ADR-0070). A view never opens a second one for a
 * topic a feature handles: a second `EventSource` is a second connection. The stream reconnects
 * indefinitely and reports its state to the header's freshness indicator (ADR-0052); while it is down
 * the per-hook refetch intervals keep every view updating.
 */
export function ClusterLayout() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const topics = [...new Set(useFeatures().flatMap((feature) => Object.keys(feature.streamTopics ?? {})))].sort(
    (a, b) => a.localeCompare(b),
  );
  useClusterStream(clusterId, topics);
  const header = useSlot('cluster.header');

  // The action host is per cluster: the dialogs row actions open live here, outside every grid, and
  // leaving the cluster closes them.
  return (
    <ActionHostProvider>
      <div className={styles.layout}>
        <div className={styles.context}>
          <div className={styles.trail}>
            <Breadcrumb />
          </div>
          {header.map(({ id, Component }) => (
            <Component key={id} clusterId={clusterId} />
          ))}
        </div>
        <Outlet />
      </div>
    </ActionHostProvider>
  );
}
