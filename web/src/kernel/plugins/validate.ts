import type { AnyRoute } from '@tanstack/react-router';

import { CONTRACT, type StudioFeature } from '../feature.ts';
import type { ManifestFeatureView } from '../manifest.ts';
import { ACTION_SECTIONS } from '../actions/types.ts';
import { NAV_GROUPS } from '../nav/groups.ts';
import type { SlotContribution, SlotContributions, SlotName } from '../slots.ts';
import { guarded } from './guarded.tsx';

/** A loaded plugin module, accepted and made safe to mount, or the reason it was not. */
export type Checked = { ok: true; feature: StudioFeature } | { ok: false; reason: string };

const NAV_GROUP_IDS = new Set<string>(NAV_GROUPS.map((group) => group.id));
const ACTION_SECTION_IDS = new Set<string>(ACTION_SECTIONS.map((section) => section.id));

/** The first segment a plugin route's path must have: everything it adds lives under `p/<id>/`. */
export function pluginPathPrefix(id: string): string {
  return `p/${id}`;
}

function routePath(route: AnyRoute): string {
  const path = (route.options as { path?: unknown }).path;
  return typeof path === 'string' ? path.replace(/^\//, '') : '';
}

function underPrefix(path: string, id: string): boolean {
  const prefix = pluginPathPrefix(id);
  return path === prefix || path.startsWith(`${prefix}/`);
}

/**
 * Accepts what a plugin's bundle exported only when it keeps to its own namespace (ADR-0100):
 * the contract it was built for, its own id, routes and navigation under `p/<id>/`, slot entries
 * named `<id>.…`, only the stream topics its descriptor declared, and navigation groups the shell
 * has. Every component it contributes to a shared screen is wrapped so a throw stays its own.
 */
export function checkPlugin(entry: ManifestFeatureView, exported: unknown): Checked {
  const id = entry.id;
  if (!exported || typeof exported !== 'object') {
    return { ok: false, reason: 'its bundle has no default export describing the plugin' };
  }
  const feature = exported as StudioFeature;
  if (feature.contract !== CONTRACT) {
    return {
      ok: false,
      reason: `it was built for extension contract ${String(feature.contract)}, and this Studio has ${CONTRACT}`,
    };
  }
  if (feature.id !== id) {
    return { ok: false, reason: `its bundle calls itself ${String(feature.id)}` };
  }
  for (const route of [...(feature.routes?.root ?? []), ...(feature.routes?.cluster ?? [])]) {
    if (!underPrefix(routePath(route), id)) {
      return { ok: false, reason: `its route "${routePath(route)}" is outside ${pluginPathPrefix(id)}/` };
    }
  }
  for (const nav of feature.nav ?? []) {
    if (!NAV_GROUP_IDS.has(nav.group)) return { ok: false, reason: `it names no such navigation group "${nav.group}"` };
    if (!underPrefix(nav.path.replace(/^\//, ''), id)) {
      return { ok: false, reason: `its navigation entry "${nav.label}" leads outside ${pluginPathPrefix(id)}/` };
    }
  }
  const declaredTopics = new Set(entry.topics);
  for (const topic of Object.keys(feature.streamTopics ?? {})) {
    if (!declaredTopics.has(topic)) return { ok: false, reason: `it handles stream topic "${topic}", which it never declared` };
  }

  const slots: SlotContributions = {};
  for (const [name, contributions] of Object.entries(feature.slots ?? {}) as [SlotName, SlotContribution<never>[]][]) {
    // Where a resource name links to is Studio's own: a plugin that could redirect every queue
    // link would be a phishing surface inside the console (ADR-0107).
    if (name.endsWith('.link')) {
      return { ok: false, reason: `it contributes to ${name}, and links to built-in resources are Studio's own` };
    }
    const action = name.endsWith('.actions');
    for (const contribution of contributions) {
      if (!contribution.id.startsWith(`${id}.`)) {
        return { ok: false, reason: `its ${name} entry "${contribution.id}" is not named ${id}.…` };
      }
      if (action && !ACTION_SECTION_IDS.has(String(contribution.section))) {
        return {
          ok: false,
          reason: `its ${name} entry "${contribution.id}" names no menu section (one of ${[...ACTION_SECTION_IDS].join(', ')})`,
        };
      }
    }
    // Header, badge-like and menu slots stay silent on failure — a sentence inside a menu is not a
    // menu item; panels and tabs say what happened.
    const quiet = name === 'shell.header' || name === 'topology.node.marks' || action;
    (slots as Record<string, SlotContribution<never>[]>)[name] = contributions.map((contribution) => ({
      ...contribution,
      // A plugin's settings are listed under Plugins, never mixed into Studio's own groups.
      group: name === 'settings.sections' ? 'plugins' : contribution.group,
      Component: guarded(id, contribution.Component, quiet),
    }));
  }

  return {
    ok: true,
    feature: {
      ...feature,
      slots,
      nav: feature.nav?.map((nav) => (nav.Badge ? { ...nav, Badge: guarded(id, nav.Badge, true) } : nav)),
      palette: feature.palette ? guarded(id, feature.palette, true) : undefined,
    },
  };
}
