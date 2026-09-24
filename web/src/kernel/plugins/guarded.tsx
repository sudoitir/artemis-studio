import type { ComponentType } from 'react';

import { PluginBoundary } from './PluginBoundary.tsx';

/** `Inner` inside a {@link PluginBoundary}, with the same props. */
export function guarded<P extends object>(pluginId: string, Inner: ComponentType<P>, quiet = false): ComponentType<P> {
  function Guarded(props: P) {
    return (
      <PluginBoundary pluginId={pluginId} quiet={quiet}>
        <Inner {...props} />
      </PluginBoundary>
    );
  }
  Guarded.displayName = `Plugin(${pluginId})`;
  return Guarded;
}
