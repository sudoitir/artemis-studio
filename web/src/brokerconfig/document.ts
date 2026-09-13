import type {
  ConfigAddressSettingView,
  ConfigAddressView,
  ConfigDivertView,
  ConfigDocumentView,
  ConfigSecuritySettingView,
} from '../api/client.ts';

/**
 * Pure edits to a declaration document. The editors change one item; the whole
 * document is what gets saved, against the revision it was read from, so these
 * helpers are the only place an item's identity (its match or name) is decided.
 */

export const EMPTY_DOCUMENT: ConfigDocumentView = {
  version: 1,
  addresses: [],
  addressSettings: [],
  securitySettings: [],
  diverts: [],
};

export function upsertAddressSetting(
  doc: ConfigDocumentView,
  item: ConfigAddressSettingView,
  originalMatch?: string,
): ConfigDocumentView {
  return { ...doc, addressSettings: upsert(doc.addressSettings, item, (i) => i.match, originalMatch) };
}

export function upsertSecuritySetting(
  doc: ConfigDocumentView,
  item: ConfigSecuritySettingView,
  originalMatch?: string,
): ConfigDocumentView {
  return { ...doc, securitySettings: upsert(doc.securitySettings, item, (i) => i.match, originalMatch) };
}

export function upsertDivert(
  doc: ConfigDocumentView,
  item: ConfigDivertView,
  originalName?: string,
): ConfigDocumentView {
  return { ...doc, diverts: upsert(doc.diverts, item, (i) => i.name, originalName) };
}

export function upsertAddress(
  doc: ConfigDocumentView,
  item: ConfigAddressView,
  originalName?: string,
): ConfigDocumentView {
  return { ...doc, addresses: upsert(doc.addresses, item, (i) => i.name, originalName) };
}

export function removeItem(
  doc: ConfigDocumentView,
  section: 'addresses' | 'addressSettings' | 'securitySettings' | 'diverts',
  key: string,
): ConfigDocumentView {
  switch (section) {
    case 'addresses':
      return { ...doc, addresses: doc.addresses.filter((i) => i.name !== key) };
    case 'addressSettings':
      return { ...doc, addressSettings: doc.addressSettings.filter((i) => i.match !== key) };
    case 'securitySettings':
      return { ...doc, securitySettings: doc.securitySettings.filter((i) => i.match !== key) };
    case 'diverts':
      return { ...doc, diverts: doc.diverts.filter((i) => i.name !== key) };
  }
}

/** Whether `key` names an item other than the one being edited — the uniqueness a form validates. */
export function keyTaken<T>(items: T[], keyOf: (item: T) => string, key: string, original?: string): boolean {
  return items.some((i) => keyOf(i) === key && keyOf(i) !== original);
}

function upsert<T>(items: T[], item: T, keyOf: (item: T) => string, original?: string): T[] {
  const target = original ?? keyOf(item);
  const index = items.findIndex((i) => keyOf(i) === target);
  if (index < 0) return [...items, item];
  const next = items.slice();
  next[index] = item;
  return next;
}

/**
 * `patch` laid over `base`: every item in the patch adds or replaces its
 * counterpart by key, and everything else in `base` stays. Address settings
 * merge by *values* too — the pasted keys win, the rest of the entry survives —
 * because the broker replaces the whole entry on apply (ADR-0067 D5) and a
 * fragment that sets four keys must not silently reset the other fourteen.
 */
export function mergeDocuments(base: ConfigDocumentView, patch: ConfigDocumentView): ConfigDocumentView {
  let doc = base;
  for (const a of patch.addresses) doc = upsertAddress(doc, a);
  for (const s of patch.addressSettings) {
    const existing = doc.addressSettings.find((i) => i.match === s.match);
    doc = upsertAddressSetting(doc, existing ? { ...existing, values: { ...existing.values, ...s.values } } : s);
  }
  for (const s of patch.securitySettings) doc = upsertSecuritySetting(doc, s);
  for (const d of patch.diverts) doc = upsertDivert(doc, d);
  return doc;
}
