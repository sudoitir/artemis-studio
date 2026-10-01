import { describe, expect, it } from 'vitest';

import type { PluginUpdateView, PluginView } from './api.ts';
import { pluginColumns } from './columns.ts';

const plugin = (over: Partial<PluginView> = {}): PluginView =>
  ({
    id: 'acme-notes',
    version: '1.0.0',
    status: 'active',
    progress: null,
    stuck: false,
    verified: true,
    info: {
      title: 'Notes',
      vendor: { name: 'Acme' },
      contributions: { ui: true, permissions: [], settingKeys: [], streamTopics: [], mcpTools: [] },
    },
    ...over,
  }) as PluginView;

const columns = (updates: PluginUpdateView[] = []) =>
  pluginColumns({
    updates: new Map(updates.map((u) => [u.id, u])),
    onUpdate: () => {},
    onOpen: () => {},
    showLicense: false,
  });

describe('plugin columns', () => {
  it('never hides the plugin, and measures the version with the update beside it', () => {
    const [name, version] = columns([
      { id: 'acme-notes', currentVersion: '1.0.0', availableVersion: '1.1.0', error: null },
    ]);
    expect(name.priority).toBe('essential');
    expect(version.accessor(plugin())).toBe('1.0.0 1.1.0 available');
  });

  it('measures the status as the cell words it, and the fix as its button label', () => {
    const [, , status, , fix] = columns();
    expect(status.accessor(plugin({ status: 'activating', progress: 'migrating' }))).toBe('Activating · migrating');
    expect(status.accessor(plugin({ stuck: true }))).toBe('Did not stop cleanly');
    expect(fix.accessor(plugin())).toBe('');
    expect(fix.accessor(plugin({ status: 'failed' }))).toBe('See why');
    expect(fix.accessor(plugin({ status: 'needs_restart' }))).toBe('Details');
  });
});
