import { Loader, Text } from '@mantine/core';

import { useClusters, useEnvironments, type ClusterSummary, type EnvironmentView } from '../api/client.ts';
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
 *
 * Clusters are grouped by environment (sorted by `sortOrder`), each with a
 * colour dot leading its group label — an admin-chosen colour, not a semantic
 * token, so it's read directly from the environment record rather than a
 * `--as-*` var (same exception `EnvironmentsPanel`'s `ColorSwatch` already
 * takes). Environment-less clusters render ungrouped, first.
 */
export function ClusterRailNav({ collapsed }: { collapsed: boolean }) {
  const clusters = useClusters();
  const environments = useEnvironments();

  if (!clusters.data) {
    return <Loader size="sm" />;
  }

  const envById = new Map((environments.data ?? []).map((e) => [e.id, e]));
  const sortedEnvs = [...(environments.data ?? [])].sort((a, b) => a.sortOrder - b.sortOrder);
  const ungrouped = clusters.data.filter((c) => !c.environmentId || !envById.has(c.environmentId));
  const groups: { env: EnvironmentView | null; clusters: ClusterSummary[] }[] = [
    ...(ungrouped.length > 0 ? [{ env: null, clusters: ungrouped }] : []),
    ...sortedEnvs
      .map((env) => ({ env, clusters: clusters.data!.filter((c) => c.environmentId === env.id) }))
      .filter((g) => g.clusters.length > 0),
  ];

  return (
    <nav aria-label="Clusters">
      {groups.map(({ env, clusters: groupClusters }) => (
        <div key={env?.id ?? 'ungrouped'}>
          {env && !collapsed ? (
            <Text
              size="10px"
              fw={700}
              tt="uppercase"
              c="dimmed"
              px="xs"
              pt="sm"
              style={{ display: 'flex', alignItems: 'center', gap: 6 }}
            >
              <span className={styles.envDot} style={{ background: env.colour ?? 'var(--as-border)' }} aria-hidden="true" />
              {env.name}
            </Text>
          ) : null}
          {groupClusters.map((c) => (
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
        </div>
      ))}
      <div className={styles.footer}>
        <RegisterClusterButton />
      </div>
    </nav>
  );
}
