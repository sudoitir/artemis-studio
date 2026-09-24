import { createElement, type ComponentType, type ReactElement } from 'react';
import { createRootRoute, createRoute } from '@tanstack/react-router';

import { LoginView } from '../auth/LoginView.tsx';
import type { ModuleId } from '../feature.ts';
import { PluginUnavailable } from '../plugins/PluginUnavailable.tsx';
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

/**
 * The open tab is an `admin.tabs` contribution's id; the page falls back to the first tab for any
 * other. `plugin` is the plugin whose details are open on the Plugins tab; `upload` reopens an
 * inspected upload's review, which is where a single-sign-on step-up returns to.
 */
const adminRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'admin',
  component: AdminView,
  validateSearch: (raw: Record<string, unknown>): { tab?: string; plugin?: string; upload?: string } => ({
    ...(typeof raw.tab === 'string' && raw.tab ? { tab: raw.tab } : {}),
    ...(typeof raw.plugin === 'string' && raw.plugin ? { plugin: raw.plugin } : {}),
    ...(typeof raw.upload === 'string' && /^[0-9a-f]{64}$/.test(raw.upload) ? { upload: raw.upload } : {}),
  }),
});

const accountRoute = createRoute({ getParentRoute: () => rootRoute, path: 'account', component: AccountView });

/**
 * Every address under a plugin's `p/<id>/` that none of its own routes answer — including every
 * address of a plugin that is not running — explains why (ADR-0100). A plugin's own routes are
 * more specific, so they always win.
 */
const pluginRootFallback = createRoute({
  getParentRoute: () => rootRoute,
  path: 'p/$pluginId/$',
  component: PluginUnavailable,
});

export const pluginClusterFallback = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'p/$pluginId/$',
  component: PluginUnavailable,
});

/** The shell's own pages, beside the cluster layout. */
export const shellRoutes = [indexRoute, loginRoute, adminRoute, accountRoute, pluginRootFallback];

/** A feature's view, or the page explaining that the feature is disabled on this installation (feature-modules spec). */
export function featureView(feature: ModuleId, View: ComponentType): () => ReactElement {
  return function FeatureView() {
    return createElement(FeatureGate, { feature, children: createElement(View) });
  };
}
