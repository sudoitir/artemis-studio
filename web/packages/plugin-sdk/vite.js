import { federation } from '@module-federation/vite';

import { SDK, SHARED_LIBRARIES, packageOf } from './shared.js';
import peers from './peers.json' with { type: 'json' };

/**
 * Vite configuration for a plugin's UI (ADR-0100): a Module Federation remote named after the
 * plugin, exposing `./feature`, that takes React, Mantine, TanStack and this SDK from the running
 * Studio (`import: false`), with a relative base so it is served from wherever Studio mounts it,
 * built into the jar's `META-INF/artemis-studio/ui/`.
 *
 * The build fails on an import Studio does not share — `@mantine/notifications`, say, or
 * Mantine's CSS — since a plugin's own copy would load beside Studio's and silently misbehave.
 *
 * @param {{ id: string, entry?: string, outDir?: string }} options
 * @returns {import('vite').PluginOption[]}
 */
export function studioPlugin({ id, entry = './src/feature.tsx', outDir = 'target/classes/META-INF/artemis-studio/ui' }) {
  if (!/^[a-z][a-z0-9]*(-[a-z0-9]+)+$/.test(id)) {
    throw new Error(`studioPlugin: "${id}" is not a plugin id (lowercase kebab-case, at least two segments, as in plugin.json)`);
  }
  const shared = Object.fromEntries([
    ...SHARED_LIBRARIES.map((name) => [
      name,
      { singleton: true, import: false, requiredVersion: peers[packageOf(name)] },
    ]),
    [SDK, { singleton: true, import: false, requiredVersion: false }],
  ]);
  const allowed = new Set([...SHARED_LIBRARIES.map(packageOf), SDK]);

  return [
    {
      name: 'artemis-studio-plugin',
      // A remote has no index.html: the exposed module is the build's input, remoteEntry.js its entry.
      config: () => ({
        base: './',
        build: { outDir, emptyOutDir: true, target: 'esnext', rolldownOptions: { input: entry } },
      }),
      resolveId(source, importer) {
        if (!importer || !source.startsWith('@mantine/')) return null;
        if (allowed.has(packageOf(source)) && !source.endsWith('.css')) return null;
        this.error(
          `${source} is not shared by Studio, so a plugin would load its own copy beside Studio's. ` +
            (source.startsWith('@mantine/notifications')
              ? 'Use notify from @artemis-studio/plugin-sdk instead.'
              : source.endsWith('.css')
                ? "Studio already loads Mantine's styles; remove this import."
                : 'Use @mantine/core and @mantine/hooks only.'),
        );
      },
    },
    federation({
      name: `plugin_${id.replace(/-/g, '_')}`,
      filename: 'remoteEntry.js',
      exposes: { './feature': entry },
      shared,
      dts: false,
    }),
  ];
}
