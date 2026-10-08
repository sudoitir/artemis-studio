import type { Setting, SettingChange } from './api.ts';

/** `n` with the singular or plural noun after it: "1 change", "2 changes". */
export const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;

/** One settings category: the settings of one module, or of one plugin, shown as one tab. */
export interface Category {
  id: string;
  title: string;
  /** Studio's own modules are listed under Studio, an installed plugin's under Plugins. */
  group: 'studio' | 'plugins';
  /** In the order the server sends them, which is the order their module declares them. */
  keys: string[];
}

/**
 * The categories of `settings`, by title. A plugin's settings are namespaced by its id, so a category is a
 * plugin's when the manifest lists an installed plugin with that id (`plugins`, id to title), and is then
 * named after the plugin; any other is Studio's.
 */
export function categoriesOf(settings: Record<string, Setting>, plugins: ReadonlyMap<string, string>) {
  const byId = new Map<string, Category>();
  for (const [key, setting] of Object.entries(settings)) {
    const existing = byId.get(setting.category);
    if (existing) {
      existing.keys.push(key);
      continue;
    }
    byId.set(setting.category, {
      id: setting.category,
      title: plugins.get(setting.category) ?? setting.categoryTitle,
      group: plugins.has(setting.category) ? 'plugins' : 'studio',
      keys: [key],
    });
  }
  return [...byId.values()].sort((a, b) => a.title.localeCompare(b.title));
}

/** The words of a search, lower-cased; a setting matches when it contains every one. */
export function searchTerms(query: string | undefined): string[] {
  return (query ?? '').toLowerCase().split(/\s+/).filter(Boolean);
}

/** Whether a setting matches every term, in its key, label, hint or category. */
export function matches(key: string, setting: Setting, terms: readonly string[]): boolean {
  if (terms.length === 0) return true;
  const text = `${key} ${setting.label} ${setting.hint} ${setting.categoryTitle}`.toLowerCase();
  return terms.every((term) => text.includes(term));
}

/** A setting as the draft holds it: the value shown in its input, or a reset to its default. */
export interface DraftField {
  key: string;
  kind: string;
  value: string;
  reset: boolean;
}

export const fieldOf = (key: string, setting: Setting): DraftField => ({
  key,
  kind: setting.kind,
  value: setting.value,
  reset: false,
});

/** The change a draft field makes to the stored setting, or null when it makes none. */
export function changeOf(field: DraftField, setting: Setting | undefined): SettingChange | null {
  if (!setting) return null;
  if (field.reset) return setting.overridden ? { key: field.key, reset: true } : null;
  return field.value === setting.value ? null : { key: field.key, value: field.value };
}

/** What a change sets, in words: its value, or the default it resets to. */
export const changedTo = (change: SettingChange, setting: Setting) =>
  change.reset ? `${setting.defaultValue} (default)` : (change.value ?? '');

const WHOLE_NUMBER = /^-?\d+$/;

/**
 * What can be said about a value without asking the server: that it is missing, not a whole number, or a
 * cron expression with the wrong number of fields. Ranges and duration syntax are the server's, through the
 * preview, so the two can never disagree.
 */
export function localError(kind: string, value: string): string | null {
  const v = value.trim();
  if (kind === 'BOOLEAN') return null;
  if (!v) return 'Enter a value. To go back to the default, use Reset to default.';
  if (kind === 'INT' && !WHOLE_NUMBER.test(v)) return 'Enter a whole number.';
  if (kind === 'CRON' && v.split(/\s+/).length !== 6) {
    return 'Enter six fields: second, minute, hour, day of month, month and day of week.';
  }
  return null;
}

/** The range a bounded setting accepts, in the setting's own syntax; empty when only the kind limits it. */
export function boundsText({ kind, min, max }: Pick<Setting, 'kind' | 'min' | 'max'>): string {
  if (kind === 'INT') {
    return max == null ? `A whole number, at least ${min ?? 1}.` : `A whole number from ${min ?? 1} to ${max}.`;
  }
  if (kind !== 'DURATION' && kind !== 'DURATION_OR_OFF') return '';
  const forever = max === 'forever';
  const ceiling = max == null || forever ? null : max;
  let range = '';
  if (min != null && ceiling != null) range = `From ${min} to ${ceiling}.`;
  else if (min != null) range = `At least ${min}.`;
  else if (ceiling != null) range = `At most ${ceiling}.`;
  return forever ? `${range} Enter forever for no limit.`.trim() : range;
}
