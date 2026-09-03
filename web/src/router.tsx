import {
  createRootRoute,
  createRoute,
  createRouter,
  redirect,
} from '@tanstack/react-router';
import type { QueryClient } from '@tanstack/react-query';

import { RootLayout } from './app/RootLayout.tsx';
import { RouteError } from './app/RouteError.tsx';
import { ClusterLayout } from './app/ClusterLayout.tsx';
import { HomeView } from './app/HomeView.tsx';
import { TopologyView } from './topology/TopologyView.tsx';
import { QueuesView } from './queues/QueuesView.tsx';
import { ResourceView } from './resources/ResourceView.tsx';
import { SettingsView } from './settings/SettingsView.tsx';

/** Navigable state that belongs in the URL, not local state (non-negotiable #9). */
export interface ResourceSearch {
  q?: string;
  sort?: string;
  page?: number;
}

function validateResourceSearch(raw: Record<string, unknown>): ResourceSearch {
  const out: ResourceSearch = {};
  if (typeof raw.q === 'string' && raw.q) out.q = raw.q;
  if (typeof raw.sort === 'string' && raw.sort) out.sort = raw.sort;
  const page = Number(raw.page);
  if (Number.isFinite(page) && page > 1) out.page = Math.floor(page);
  return out;
}

const rootRoute = createRootRoute({ component: RootLayout });

const indexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/',
  component: HomeView,
});

const clusterRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'clusters/$clusterId',
  component: ClusterLayout,
  errorComponent: RouteError,
});

const clusterIndexRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: '/',
  beforeLoad: ({ params }) => {
    throw redirect({ to: `/clusters/${params.clusterId}/topology` });
  },
});

const topologyRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'topology',
  component: TopologyView,
  errorComponent: RouteError,
});

const queuesRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'queues',
  component: QueuesView,
  validateSearch: validateResourceSearch,
  errorComponent: RouteError,
});

const resourceKinds = [
  'addresses',
  'consumers',
  'sessions',
  'connections',
  'producers',
] as const;

const resourceRoutes = resourceKinds.map((kind) =>
  createRoute({
    getParentRoute: () => clusterRoute,
    path: kind,
    component: () => <ResourceView kind={kind} />,
    validateSearch: validateResourceSearch,
    errorComponent: RouteError,
  }),
);

const settingsRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'settings',
  component: SettingsView,
  errorComponent: RouteError,
});

const routeTree = rootRoute.addChildren([
  indexRoute,
  clusterRoute.addChildren([
    clusterIndexRoute,
    topologyRoute,
    queuesRoute,
    ...resourceRoutes,
    settingsRoute,
  ]),
]);

export function createAppRouter(queryClient: QueryClient) {
  return createRouter({
    routeTree,
    context: { queryClient },
    defaultPreload: 'intent',
    scrollRestoration: true,
  });
}

export type AppRouter = ReturnType<typeof createAppRouter>;
