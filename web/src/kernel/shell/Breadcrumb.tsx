import { Anchor, Breadcrumbs, Text } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import { useCurrentView } from '../nav/currentView.ts';
import { useTitleParts } from './pageTitle.ts';

/**
 * Where the operator is inside a cluster (ADR-0109): cluster › group › view › open resource. The
 * last crumb is the current page; the ones before it that are places are links back to them.
 */
export function Breadcrumb() {
  const view = useCurrentView();
  const { cluster, resource } = useTitleParts();
  if (!view) return null;

  const crumbs: { label: string; to?: string }[] = [
    { label: cluster ?? 'Cluster', to: `/clusters/${view.clusterId}` },
  ];
  if (view.item) {
    if (view.groupLabel) crumbs.push({ label: view.groupLabel });
    crumbs.push({ label: view.item.label, to: `/clusters/${view.clusterId}/${view.item.path}` });
  }
  if (resource) crumbs.push({ label: resource });

  return (
    <nav aria-label="Breadcrumb">
      <Breadcrumbs separator="›" separatorMargin={6} fz="xs">
        {crumbs.map((crumb, i) => {
          const last = i === crumbs.length - 1;
          return last || !crumb.to ? (
            <Text key={i} size="xs" c="dimmed" aria-current={last ? 'page' : undefined} truncate maw="40ch">
              {crumb.label}
            </Text>
          ) : (
            <Anchor key={i} component={Link} to={crumb.to} size="xs" c="dimmed">
              {crumb.label}
            </Anchor>
          );
        })}
      </Breadcrumbs>
    </nav>
  );
}
