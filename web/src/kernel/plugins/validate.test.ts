import { describe, expect, it } from 'vitest';
import { createRoute } from '@tanstack/react-router';

import { pluginEntry } from '../../test/manifest.ts';
import { CONTRACT } from '../feature.ts';
import { clusterRoute, rootRoute } from '../routing/roots.ts';
import { checkPlugin } from './validate.ts';

const Panel = () => null;
const entry = pluginEntry('acme-notes', { topics: ['acme-notes'] });

function plugin(overrides: Record<string, unknown> = {}) {
  return { contract: CONTRACT, id: 'acme-notes', ...overrides };
}

describe('checkPlugin', () => {
  it('accepts a plugin that keeps to its namespace', () => {
    const route = createRoute({ getParentRoute: () => clusterRoute, path: 'p/acme-notes/notes', component: Panel });
    const checked = checkPlugin(
      entry,
      plugin({
        routes: { cluster: [route] },
        nav: [{ group: 'observe', order: 1, label: 'Notes', icon: Panel, path: 'p/acme-notes/notes' }],
        streamTopics: { 'acme-notes': () => {} },
        slots: { 'settings.sections': [{ id: 'acme-notes.settings', order: 1, title: 'Notes', group: 'cluster', Component: Panel }] },
      }),
    );
    expect(checked.ok).toBe(true);
    if (!checked.ok) return;
    const section = checked.feature.slots?.['settings.sections']?.[0];
    expect(section?.group).toBe('plugins');
    expect(section?.Component).not.toBe(Panel);
  });

  it.each([
    ['another contract', plugin({ contract: CONTRACT + 1 }), /extension contract/],
    ['another id', plugin({ id: 'acme-other' }), /calls itself/],
    ['nothing exported', undefined, /no default export/],
    [
      'a route outside its namespace',
      plugin({ routes: { root: [createRoute({ getParentRoute: () => rootRoute, path: 'admin/evil', component: Panel })] } }),
      /outside p\/acme-notes/,
    ],
    ['an undeclared topic', plugin({ streamTopics: { 'queues': () => {} } }), /never declared/],
    ['a slot id outside its namespace', plugin({ slots: { 'shell.header': [{ id: 'banner', order: 0, Component: Panel }] } }), /not named acme-notes/],
    ['an unknown navigation group', plugin({ nav: [{ group: 'nope', order: 1, label: 'X', icon: Panel, path: 'p/acme-notes' }] }), /navigation group/],
  ])('refuses %s', (_label, exported, reason) => {
    const checked = checkPlugin(entry, exported);
    expect(checked.ok).toBe(false);
    if (!checked.ok) expect(checked.reason).toMatch(reason);
  });
});
