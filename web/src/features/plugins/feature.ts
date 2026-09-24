import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { HeaderIndicator } from './HeaderIndicator.tsx';
import { PluginsPanel } from './PluginsPanel.tsx';

/** Administration → Plugins (ADR-0099, ADR-0103): install, update and remove plugins, from the UI. */
export const pluginsFeature = defineFeature({
  contract: CONTRACT,
  id: 'plugins',
  slots: {
    'admin.tabs': [{ id: 'plugins', order: 50, title: 'Plugins', Component: PluginsPanel }],
    'shell.header': [{ id: 'plugins-attention', order: 20, Component: HeaderIndicator }],
  },
});
