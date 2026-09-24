import { createRouter, type RouterHistory } from '@tanstack/react-router';
import type { QueryClient } from '@tanstack/react-query';

import type { StudioFeature } from '../kernel/feature.ts';
import { clusterRoute, pluginClusterFallback, rootRoute, shellRoutes } from '../kernel/routing/roots.ts';
import { RouteError } from '../kernel/shell/RouteError.tsx';

/**
 * The route tree: the shell's pages, then each installed feature's routes under the kernel root it
 * names (ADR-0070). Every installed feature is routed, enabled or not; a disabled feature's view
 * renders the page that explains it. `history` is the browser's unless given, as a test gives one in memory.
 */
export function createAppRouter(queryClient: QueryClient, features: StudioFeature[], history?: RouterHistory) {
  const routeTree = rootRoute.addChildren([
    ...shellRoutes,
    ...features.flatMap((feature) => feature.routes?.root ?? []),
    clusterRoute.addChildren([...features.flatMap((feature) => feature.routes?.cluster ?? []), pluginClusterFallback]),
  ]);
  return createRouter({
    routeTree,
    history,
    context: { queryClient },
    defaultPreload: 'intent',
    defaultErrorComponent: RouteError,
    scrollRestoration: true,
  });
}

export type AppRouter = ReturnType<typeof createAppRouter>;

declare module '@tanstack/react-router' {
  interface Register {
    router: AppRouter;
  }
}
