import type {
  ConfigAddressSettingView,
  ConfigAddressView,
  ConfigBridgeView,
  ConfigCatalogueView,
  ConfigDivertView,
  ConfigDocumentView,
  ConfigSecuritySettingView,
} from './api.ts';

/**
 * Readable renderings of declared values. A declaration is JSON on the wire —
 * `maxSizeBytes: 104857600` — and an operator on call should not have to count
 * digits or remember whether a delay is seconds or milliseconds. The raw value
 * stays first, because it is what the broker sees; the reading follows it.
 */

/** The broker.xml element name for a key: from the catalogue, or a mechanical fallback. */
export function prettyKey(jsonName: string, catalogue?: ConfigCatalogueView): string {
  const known = catalogue?.addressSettingKeys.find((k) => k.jsonName === jsonName);
  if (known) return known.xmlName;
  return jsonName.replace(/([a-z0-9])([A-Z])/g, '$1-$2').replace(/([A-Z]+)([A-Z][a-z])/g, '$1-$2').toLowerCase();
}

const BYTES = /Bytes$/;
const MILLIS = /(Delay|delay)$/;
const SECONDS = new Set(['slowConsumerCheckPeriod']);
const DAYS = new Set(['messageCounterHistoryDayLimit']);

function bytes(n: number): string | undefined {
  if (n < 1024) return undefined;
  const units = ['KiB', 'MiB', 'GiB', 'TiB'];
  let v = n;
  let u = -1;
  while (v >= 1024 && u < units.length - 1) {
    v /= 1024;
    u += 1;
  }
  const shown = Number.isInteger(v) ? String(v) : v.toFixed(v < 10 ? 2 : 1);
  return `${shown} ${units[u]}`;
}

function millis(n: number): string | undefined {
  if (n < 1000) return undefined;
  if (n < 60_000) return `${trim(n / 1000)} s`;
  if (n < 3_600_000) return `${trim(n / 60_000)} min`;
  if (n < 86_400_000) return `${trim(n / 3_600_000)} h`;
  return `${trim(n / 86_400_000)} d`;
}

function trim(v: number): string {
  return Number.isInteger(v) ? String(v) : v.toFixed(1).replace(/\.0$/, '');
}

/** A value with its reading: `104857600 (100 MiB)`, `2500 ms (2.5 s)`, `-1 (no limit)`. */
export function prettyValue(jsonName: string, value: unknown): string {
  if (value === null || value === undefined || value === '') return '—';
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  if (typeof value === 'number') {
    if (value === -1 && (BYTES.test(jsonName) || MILLIS.test(jsonName) || /^max|Limit$|Threshold$/.test(jsonName))) {
      return '-1 (no limit)';
    }
    if (BYTES.test(jsonName)) {
      const b = bytes(value);
      return b ? `${value.toLocaleString()} (${b})` : value.toLocaleString();
    }
    if (MILLIS.test(jsonName)) {
      const m = millis(value);
      return m ? `${value.toLocaleString()} ms (${m})` : `${value.toLocaleString()} ms`;
    }
    if (SECONDS.has(jsonName)) return `${value} s`;
    if (DAYS.has(jsonName)) return `${value} ${value === 1 ? 'day' : 'days'}`;
    return value.toLocaleString();
  }
  if (Array.isArray(value)) return value.length === 0 ? 'none' : value.map((v) => prettyValue(jsonName, v)).join(', ');
  if (typeof value === 'object') {
    return Object.entries(value as Record<string, unknown>)
      .map(([k, v]) => `${prettyKey(k)}: ${prettyValue(k, v)}`)
      .join('; ');
  }
  return String(value);
}

export interface Row {
  key: string;
  value: string;
}

/** The rows an item reads as: key beside value, in the order the operator declared them. */
export function addressSettingRows(item: ConfigAddressSettingView, catalogue?: ConfigCatalogueView): Row[] {
  return Object.entries(item.values)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([k, v]) => ({ key: prettyKey(k, catalogue), value: prettyValue(k, v) }));
}

export function securitySettingRows(item: ConfigSecuritySettingView): Row[] {
  const byRole = new Map<string, string[]>();
  for (const [type, roles] of Object.entries(item.permissions)) {
    for (const role of roles ?? []) {
      byRole.set(role, [...(byRole.get(role) ?? []), type]);
    }
  }
  return [...byRole.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([role, types]) => ({ key: role, value: types.join(', ') }));
}

export function divertRows(item: ConfigDivertView): Row[] {
  const rows: Row[] = [
    { key: 'from', value: item.address },
    { key: 'to', value: item.forwardingAddress },
    { key: 'effect', value: item.exclusive ? 'takes the message' : 'copies the message' },
  ];
  if (item.filter) rows.push({ key: 'filter', value: item.filter });
  if (item.routingType) rows.push({ key: 'routing-type', value: item.routingType });
  if (item.transformerClassName) rows.push({ key: 'transformer', value: item.transformerClassName });
  for (const [k, v] of Object.entries(item.transformerProperties ?? {}).sort(([a], [b]) => a.localeCompare(b))) {
    rows.push({ key: `transformer ${k}`, value: v });
  }
  return rows;
}

/**
 * A declared bridge's rows. The credential is a reference into Studio's vault and
 * is shown as one; there is no password to show, here or anywhere (ADR-0092).
 */
export function bridgeRows(item: ConfigBridgeView): Row[] {
  const rows: Row[] = [
    { key: 'from queue', value: item.queueName },
    { key: 'to address', value: item.forwardingAddress },
    {
      key: 'over',
      value: item.staticConnectors.length
        ? item.staticConnectors.join(', ')
        : (item.discoveryGroupName ?? 'not declared'),
    },
  ];
  if (item.filter) rows.push({ key: 'filter', value: item.filter });
  if (item.transformer?.className) {
    rows.push({ key: 'transformer', value: item.transformer.className });
    for (const [k, v] of Object.entries(item.transformer.properties ?? {}).sort(([a], [b]) => a.localeCompare(b))) {
      rows.push({ key: `transformer ${k}`, value: v });
    }
  }
  const optional: [string, unknown][] = [
    ['routing-type', item.routingType],
    ['concurrency', item.concurrency],
    ['ha', item.ha],
    ['use-duplicate-detection', item.useDuplicateDetection],
    ['retry-interval', item.retryInterval],
    ['retry-interval-multiplier', item.retryIntervalMultiplier],
    ['max-retry-interval', item.maxRetryInterval],
    ['initial-connect-attempts', item.initialConnectAttempts],
    ['reconnect-attempts', item.reconnectAttempts],
    ['confirmation-window-size', item.confirmationWindowSize],
    ['producer-window-size', item.producerWindowSize],
    ['min-large-message-size', item.minLargeMessageSize],
    ['check-period', item.checkPeriod],
    ['connection-ttl', item.connectionTtl],
    ['client-id', item.clientId],
  ];
  for (const [key, value] of optional) {
    if (value !== null && value !== undefined) rows.push({ key, value: prettyValue(key, value) });
  }
  if (item.credentialRef) rows.push({ key: 'credential', value: `${item.credentialRef} (held in Studio's vault)` });
  return rows;
}

export function addressRows(item: ConfigAddressView): Row[] {
  const rows: Row[] = [{ key: 'routing', value: item.routingTypes.join(', ') }];
  for (const q of item.queues) {
    const facts = [q.routingType, q.durable === false ? 'non-durable' : 'durable'];
    if (q.filter) facts.push(`filter ${q.filter}`);
    if (q.maxConsumers != null && q.maxConsumers !== -1) facts.push(`max ${q.maxConsumers} consumers`);
    if (q.purgeOnNoConsumers) facts.push('purge on no consumers');
    if (q.exclusive) facts.push('exclusive');
    if (q.nonDestructive) facts.push('non-destructive');
    if (q.ringSize != null && q.ringSize !== -1) facts.push(`ring ${q.ringSize}`);
    rows.push({ key: `queue ${q.name}`, value: facts.join(', ') });
  }
  return rows;
}

/** Every item of a document, keyed `section/name`, with its rows — the shape a comparison walks. */
export function documentItems(doc: ConfigDocumentView, catalogue?: ConfigCatalogueView): Map<string, Row[]> {
  const out = new Map<string, Row[]>();
  for (const x of doc.addresses) out.set(`address ${x.name}`, addressRows(x));
  for (const x of doc.addressSettings) out.set(`address-setting ${x.match}`, addressSettingRows(x, catalogue));
  for (const x of doc.securitySettings) out.set(`security-setting ${x.match}`, securitySettingRows(x));
  for (const x of doc.diverts) out.set(`divert ${x.name}`, divertRows(x));
  for (const x of doc.bridges) out.set(`bridge ${x.name}`, bridgeRows(x));
  return out;
}
