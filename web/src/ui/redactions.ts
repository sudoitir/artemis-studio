import type { components } from '../kernel/api/schema.d.ts';

type RedactionView = components['schemas']['RedactionView'];

/** The redactions that belong to one location, and to one header or property name when `path` is given. */
export function redactionsAt(
  redactions: RedactionView[],
  location: 'HEADER' | 'PROPERTY' | 'BODY',
  path?: string,
): RedactionView[] {
  return redactions.filter((r) => r.location === location && (path === undefined || r.path === path));
}
