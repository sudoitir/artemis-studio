import { Suspense, type ComponentType, type ReactNode } from 'react';
import { Loader } from '@mantine/core';
import { lazyRouteComponent } from '@tanstack/react-router';

import type { ModuleId } from '../feature.ts';
import { FeatureGate } from '../shell/FeatureGate.tsx';

/** The named export `exportName` of the module `load` imports, as the default export `lazyRouteComponent` expects. */
function namedExport<K extends string, P extends object>(
  load: () => Promise<{ [Name in NoInfer<K>]: (props: P) => ReactNode }>,
  exportName: K,
) {
  return lazyRouteComponent((): Promise<{ default: (props: P) => ReactNode }> =>
    load().then((module) => ({ default: module[exportName] })),
  );
}

/**
 * A feature's view, loaded when its route is first opened or preloaded on intent, so its code and
 * everything only it uses is a chunk of its own. A feature's `feature.ts` registers its routes at
 * startup, which makes its views the one thing it must not import statically. Behind the same
 * {@link FeatureGate} as any view: a disabled feature shows the page explaining it.
 */
export function lazyFeatureView<K extends string>(
  feature: ModuleId,
  load: () => Promise<{ [Name in NoInfer<K>]: () => ReactNode }>,
  exportName: K,
) {
  const View = namedExport(load, exportName);
  const FeatureView = () => (
    <FeatureGate feature={feature}>
      <View />
    </FeatureGate>
  );
  FeatureView.preload = () => View.preload?.();
  return FeatureView;
}

/**
 * A slot contribution whose code, and what it pulls in, loads when the slot first renders it. The
 * contribution carries its own loading state, so the screen that hosts the slot never waits for it.
 */
export function lazySlot<K extends string, P extends object>(
  load: () => Promise<{ [Name in NoInfer<K>]: (props: P) => ReactNode }>,
  exportName: K,
): ComponentType<P> {
  const Contribution = namedExport(load, exportName);
  return function LazySlot(props: P) {
    return (
      <Suspense fallback={<Loader size="sm" />}>
        <Contribution {...props} />
      </Suspense>
    );
  };
}
