import { createElement, type ComponentType, type ReactElement } from 'react';
import { createRootRoute, createRoute } from '@tanstack/react-router';

import { LoginView } from '../auth/LoginView.tsx';
import type { FeatureId } from '../feature.ts';
import { AccountView } from '../shell/AccountView.tsx';
import { AdminView } from '../shell/AdminView.tsx';
import { ClusterLayout } from '../shell/ClusterLayout.tsx';
import { FeatureGate } from '../shell/FeatureGate.tsx';
import { HomeView } from '../shell/HomeView.tsx';
import { RootLayout } from '../shell/RootLayout.tsx';

/** The root every page renders inside: the shell. A feature's `routes.root` are its children. */
export const rootRoute = createRootRoute({ component: RootLayout });

/** One cluster's layout. A feature's `routes.cluster` are its children. */
export const clusterRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'clusters/$clusterId',
  component: ClusterLayout,
});

const indexRoute = createRoute({ getParentRoute: () => rootRoute, path: '/', component: HomeView });

const loginRoute = createRoute({ getParentRoute: () => rootRoute, path: 'login', component: LoginView });

/** The open tab is an `admin.tabs` contribution's id; the page falls back to the first tab for any other. */
const adminRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'admin',
  component: AdminView,
  validateSearch: (raw: Record<string, unknown>): { tab?: string } =>
    typeof raw.tab === 'string' && raw.tab ? { tab: raw.tab } : {},
});

const accountRoute = createRoute({ getParentRoute: () => rootRoute, path: 'account', component: AccountView });

/** The shell's own pages, beside the cluster layout. */
export const shellRoutes = [indexRoute, loginRoute, adminRoute, accountRoute];

/** A feature's view, or the page explaining that the feature is disabled on this installation (feature-modules spec). */
export function featureView(feature: FeatureId, View: ComponentType): () => ReactElement {
  return function FeatureView() {
    return createElement(FeatureGate, { feature, children: createElement(View) });
  };
}
