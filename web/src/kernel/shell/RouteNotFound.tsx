import { Button } from '@mantine/core';
import { Link, useRouterState } from '@tanstack/react-router';

import { EmptyState } from '../../ui/EmptyState.tsx';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';

/**
 * An address no route matches: says which address that was, that a cluster's pages (Settings among
 * them) open from a cluster, and leads back to the start, instead of the router's bare default.
 */
export function RouteNotFound() {
  const path = useRouterState({ select: (s) => s.location.pathname });
  return (
    <Page>
      <PageHeader title="Page not found" description={`Nothing in Studio is at ${path}.`} />
      <EmptyState
        kind="empty"
        title="Check the address, or start from a cluster"
        description="A cluster's pages, Settings included, open from that cluster: choose one in the cluster switcher, or go to the start page."
        action={
          <Button component={Link} to="/" variant="default" size="xs">
            Go to the start page
          </Button>
        }
      />
    </Page>
  );
}
