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
import { MessagesView } from './messages/MessagesView.tsx';
import { AuditView } from './audit/AuditView.tsx';
import { ConfigDiffView } from './config/ConfigDiffView.tsx';
import { DlqView } from './dlq/DlqView.tsx';
import { EventsView } from './events/EventsView.tsx';
import { FlowsView } from './rr/FlowsView.tsx';
import { ResourceView } from './resources/ResourceView.tsx';
import { SettingsView } from './settings/SettingsView.tsx';
import { MetricsView } from './metrics/MetricsView.tsx';
import { METRIC_RANGES, type MetricRange } from './metrics/ranges.ts';
import { AlertsView } from './alerts/AlertsView.tsx';
import { LoginView } from './auth/LoginView.tsx';
import { ChangePasswordView } from './auth/ChangePasswordView.tsx';
import { AdminView } from './admin/AdminView.tsx';

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

/** Message-browse navigable state (ADR-0021). Selection stays ephemeral (D10), not in the URL. */
export interface MessagesSearch {
  node?: string;
  filter?: string;
  page?: number;
}

function validateMessagesSearch(raw: Record<string, unknown>): MessagesSearch {
  const out: MessagesSearch = {};
  if (typeof raw.node === 'string' && raw.node) out.node = raw.node;
  if (typeof raw.filter === 'string' && raw.filter) out.filter = raw.filter;
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

const loginRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'login',
  component: LoginView,
});

const changePasswordRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'change-password',
  component: ChangePasswordView,
});

function validateAdminSearch(raw: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  if (
    typeof raw.tab === 'string' &&
    ['users', 'roles', 'environments', 'tokens', 'oidc'].includes(raw.tab)
  ) {
    out.tab = raw.tab;
  }
  return out;
}

const adminRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'admin',
  component: AdminView,
  validateSearch: validateAdminSearch,
  errorComponent: RouteError,
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

const messagesRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'queues/$queueName/messages',
  component: MessagesView,
  validateSearch: validateMessagesSearch,
  errorComponent: RouteError,
});

function validateAuditSearch(raw: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const k of ['user', 'action', 'outcome', 'from', 'to'] as const) {
    if (typeof raw[k] === 'string' && raw[k]) out[k] = raw[k];
  }
  const page = Number(raw.page);
  if (Number.isFinite(page) && page > 1) out.page = Math.floor(page);
  return out;
}

const auditRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'audit',
  component: AuditView,
  validateSearch: validateAuditSearch,
  errorComponent: RouteError,
});

function validateRrSearch(raw: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const k of ['tab', 'state', 'address'] as const) {
    if (typeof raw[k] === 'string' && raw[k]) out[k] = raw[k];
  }
  return out;
}

const rrRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'rr',
  component: FlowsView,
  validateSearch: validateRrSearch,
  errorComponent: RouteError,
});

export interface MetricsSearch {
  range?: MetricRange;
  from?: string;
  to?: string;
}

function validateMetricsSearch(raw: Record<string, unknown>): MetricsSearch {
  const out: MetricsSearch = {};
  if (typeof raw.from === 'string' && raw.from && typeof raw.to === 'string' && raw.to) {
    out.from = raw.from;
    out.to = raw.to;
    return out;
  }
  if (typeof raw.range === 'string' && (METRIC_RANGES as readonly string[]).includes(raw.range)) {
    out.range = raw.range as MetricRange;
  }
  return out;
}

const metricsRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'metrics',
  component: MetricsView,
  validateSearch: validateMetricsSearch,
  errorComponent: RouteError,
});

function validateAlertsSearch(raw: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  if (typeof raw.tab === 'string' && ['firing', 'history', 'rules'].includes(raw.tab)) {
    out.tab = raw.tab;
  }
  return out;
}

const alertsRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'alerts',
  component: AlertsView,
  validateSearch: validateAlertsSearch,
  errorComponent: RouteError,
});

const configDiffRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'config-diff',
  component: ConfigDiffView,
  errorComponent: RouteError,
});

const dlqRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'dlq',
  component: DlqView,
  errorComponent: RouteError,
});

const eventsRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'events',
  component: EventsView,
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
  loginRoute,
  changePasswordRoute,
  adminRoute,
  clusterRoute.addChildren([
    clusterIndexRoute,
    topologyRoute,
    queuesRoute,
    messagesRoute,
    metricsRoute,
    alertsRoute,
    auditRoute,
    configDiffRoute,
    dlqRoute,
    eventsRoute,
    rrRoute,
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
