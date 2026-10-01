import { Anchor, Breadcrumbs, Text } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import { useCurrentView } from '../nav/currentView.ts';
import { useTitleParts } from './pageTitle.ts';
import classes from './Breadcrumb.module.css';

/**
 * Where the operator is inside a cluster (ADR-0109): cluster › group › view › open resource. The
 * last crumb is the current page; the ones before it that are places are links back to them.
 */
export function Breadcrumb() {
  const view = useCurrentView();
  const { cluster, resource } = useTitleParts();
  if (!view) return null;
  // Until the cluster's name arrives the line keeps its height and stays empty, so the trail appears
  // whole instead of sliding when a placeholder name is replaced.
  if (!cluster) return <div className={classes.line} aria-hidden="true" />;

  const crumbs: { label: string; to?: string }[] = [{ label: cluster, to: `/clusters/${view.clusterId}` }];
  if (view.item) {
    if (view.groupLabel) crumbs.push({ label: view.groupLabel });
    crumbs.push({ label: view.item.label, to: `/clusters/${view.clusterId}/${view.item.path}` });
  }
  if (resource) crumbs.push({ label: resource });

  return (
    <nav aria-label="Breadcrumb" className={classes.line}>
      <Breadcrumbs separator="›" separatorMargin="xs" fz="xs">
        {crumbs.map((crumb, i) => {
          const last = i === crumbs.length - 1;
          return last || !crumb.to ? (
            <Text
              key={crumb.to ?? crumb.label}
              size="xs"
              c="dimmed"
              aria-current={last ? 'page' : undefined}
              truncate
              maw="40ch"
            >
              {crumb.label}
            </Text>
          ) : (
            <Anchor key={crumb.to ?? crumb.label} component={Link} to={crumb.to} size="xs" c="dimmed">
              {crumb.label}
            </Anchor>
          );
        })}
      </Breadcrumbs>
    </nav>
  );
}
