import { createRouter, type RouterHistory } from '@tanstack/react-router';
import type { QueryClient } from '@tanstack/react-query';

import type { StudioFeature } from '../kernel/feature.ts';
import { clusterRoute, pluginClusterFallback, rootRoute, shellRoutes } from '../kernel/routing/roots.ts';
import { RouteError } from '../kernel/shell/RouteError.tsx';
import { RouteNotFound } from '../kernel/shell/RouteNotFound.tsx';

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
  const router = createRouter({
    routeTree,
    history,
    context: { queryClient },
    defaultPreload: 'intent',
    defaultErrorComponent: RouteError,
    defaultNotFoundComponent: RouteNotFound,
    scrollRestoration: true,
  });
  keepScrollOnSamePage(router);
  return router;
}

/**
 * A change of the page's own search (a filter, the open section, the sort) must not throw the reader
 * back to the top: the router resets the scroll on every navigation unless told not to, and the 40 or
 * so call sites that navigate to `.` would each have to say so. Moving to another page still does.
 */
function keepScrollOnSamePage(router: { navigate: (options: never) => Promise<void> }) {
  const navigate = router.navigate.bind(router) as (options: Record<string, unknown>) => Promise<void>;
  router.navigate = ((options: Record<string, unknown>) =>
    navigate(
      options.to === '.' && options.resetScroll === undefined ? { ...options, resetScroll: false } : options,
    )) as never;
}

export type AppRouter = ReturnType<typeof createAppRouter>;

declare module '@tanstack/react-router' {
  interface Register {
    router: AppRouter;
  }
}
