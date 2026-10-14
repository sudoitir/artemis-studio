import type { PluginOption } from 'vite';

/** Options for {@link rahdarPlugin}. */
export interface RahdarPluginOptions {
  /** The plugin's id, exactly as its `plugin.json` declares it. */
  id: string;
  /** The module whose default export is the plugin (`definePlugin(...)`). Default `./src/feature.tsx`. */
  entry?: string;
  /** Where the bundle goes. Default `target/classes/META-INF/rahdar/ui`, inside the plugin jar. */
  outDir?: string;
}

/** Vite configuration for a plugin's UI: a Module Federation remote that shares Rahdar's libraries. */
export function rahdarPlugin(options: RahdarPluginOptions): PluginOption[];
