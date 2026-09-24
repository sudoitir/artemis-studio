import type { PluginOption } from 'vite';

/** Options for {@link studioPlugin}. */
export interface StudioPluginOptions {
  /** The plugin's id, exactly as its `plugin.json` declares it. */
  id: string;
  /** The module whose default export is the plugin (`definePlugin(...)`). Default `./src/feature.tsx`. */
  entry?: string;
  /** Where the bundle goes. Default `target/classes/META-INF/artemis-studio/ui`, inside the plugin jar. */
  outDir?: string;
}

/** Vite configuration for a plugin's UI: a Module Federation remote that shares Studio's libraries. */
export function studioPlugin(options: StudioPluginOptions): PluginOption[];
