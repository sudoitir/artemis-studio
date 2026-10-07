import type { ConfigDiffView, ConfigKeyView } from './api.ts';

/** Which keys the comparison lists: the drift it is for, the differences set aside, or every key. */
export type DiffFilter = 'drift' | 'expected' | 'all';

export const DIFF_FILTERS: readonly DiffFilter[] = ['drift', 'expected', 'all'];

/** One key of one section, as the table lists it. */
export interface DiffRow extends ConfigKeyView {
  section: string;
  sectionLabel: string;
}

export function diffRows(data: ConfigDiffView): DiffRow[] {
  return data.sections.flatMap((s) => s.keys.map((k) => ({ ...k, section: s.section, sectionLabel: s.label })));
}

export const rowKey = (row: DiffRow) => `${row.section}${row.key}`;

/** A difference that is correct by design: listed on its own, never counted as drift. */
export function isExpected(row: ConfigKeyView): boolean {
  return row.classification === 'EXPECTED' && row.state !== 'SAME';
}

/** The nodes that differ on a key: the outliers, or every node when no value holds a majority. */
function differingNodes(row: ConfigKeyView): string[] {
  const nodes = row.majority === null ? row.valueGroups.flatMap((g) => g.nodes) : row.outliers;
  return nodes.map((n) => n.nodeId);
}

export interface DiffQuery {
  filter: DiffFilter;
  /** Matches a key, its section, or any node's value, ignoring case. */
  text: string;
  /** Keeps the keys that differ on at least one of these nodes; none keeps every key. */
  nodes: readonly string[];
}

export function filterRows(rows: DiffRow[], { filter, text, nodes }: DiffQuery): DiffRow[] {
  const needle = text.trim().toLowerCase();
  return rows.filter((row) => {
    if (filter === 'drift' && !row.drift) return false;
    if (filter === 'expected' && !isExpected(row)) return false;
    if (nodes.length > 0) {
      const differing = differingNodes(row);
      if (!nodes.some((n) => differing.includes(n))) return false;
    }
    if (!needle) return true;
    const haystack = [row.key, row.sectionLabel, row.majority ?? '', ...row.values.map((v) => v.value ?? '')];
    return haystack.some((h) => h.toLowerCase().includes(needle));
  });
}

/** What the majority column says: the value, or that no value holds one. */
export function majorityWords(row: ConfigKeyView): string {
  return row.majority ?? 'no majority';
}

/**
 * Who differs and how, in words: "broker-3 differs: NIO", "broker-4 missing", or, with no majority,
 * every value with its nodes. A row whose nodes all agree has nothing to say.
 */
export function outlierWords(row: ConfigKeyView): string {
  if (row.majority === null) {
    return row.valueGroups.map((g) => `${g.value} on ${g.nodes.map((n) => n.nodeName).join(', ')}`).join(' · ');
  }
  return row.outliers
    .map((o) => (o.missing ? `${o.nodeName} missing` : `${o.nodeName} differs: ${o.value}`))
    .join('; ');
}

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;

/**
 * The one sentence a comparison opens with. A clean cluster says so and counts the expected
 * differences it set aside; a node that did not answer is named, because a clean result over fewer
 * nodes is not the same statement.
 */
export function summaryWords(data: ConfigDiffView): string {
  const answered = data.nodes.filter((n) => n.available).length;
  const silent = data.nodes.filter((n) => !n.available).map((n) => n.nodeName);
  const { driftKeys, driftNodes, expectedKeys } = data.summary;
  const expected =
    expectedKeys > 0 ? ` ${plural(expectedKeys, 'expected difference', 'expected differences')} set aside.` : '';
  const head =
    driftKeys > 0
      ? `${plural(driftKeys, 'key drifts', 'keys drift')} on ${plural(driftNodes, 'node', 'nodes')}.`
      : `No key drifts across the ${plural(answered, 'node', 'nodes')} compared.`;
  const rest =
    silent.length > 0
      ? ` ${silent.join(', ')} did not answer, so ${silent.length === 1 ? 'it was' : 'they were'} not compared.`
      : '';
  return `${head}${expected}${rest}`;
}
