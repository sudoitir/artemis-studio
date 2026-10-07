import type { ConfigDiffView, ConfigKeyView, ConfigNodeValueView, ConfigNodeView } from './api.ts';

/** Test fixtures for the node comparison. Placeholder names only. */

export const A = { nodeId: 'n-a', nodeName: 'broker-1' };
export const B = { nodeId: 'n-b', nodeName: 'broker-2' };
export const C = { nodeId: 'n-c', nodeName: 'broker-3' };

export function node(who: { nodeId: string; nodeName: string }, over: Partial<ConfigNodeView> = {}): ConfigNodeView {
  return { ...who, available: true, active: true, reducedSurface: false, ...over };
}

export function value(who: { nodeId: string; nodeName: string }, v: string | null): ConfigNodeValueView {
  return { ...who, value: v, missing: v === null };
}

/** A key every node agrees on. */
export function same(key: string, v: string, classification = 'DRIFT'): ConfigKeyView {
  return {
    key,
    state: 'SAME',
    stateWord: 'same',
    classification,
    drift: false,
    values: [value(A, v), value(B, v), value(C, v)],
    majority: v,
    outliers: [],
    valueGroups: [],
  };
}

/** A key on which `who` differs from the other two. */
export function outlier(
  key: string,
  majority: string,
  who: { nodeId: string; nodeName: string },
  v: string | null,
  classification = 'DRIFT',
): ConfigKeyView {
  const values = [A, B, C].map((n) => (n.nodeId === who.nodeId ? value(n, v) : value(n, majority)));
  return {
    key,
    state: v === null ? 'MISSING_ON_SOME' : 'DIFFERENT',
    stateWord: v === null ? 'missing on some' : 'different',
    classification,
    drift: classification === 'DRIFT',
    values,
    majority,
    outliers: [value(who, v)],
    valueGroups: [],
  };
}

/** A key on which every node holds its own value, so none is the majority. */
export function split(key: string, classification = 'DRIFT'): ConfigKeyView {
  const values = [value(A, '1'), value(B, '2'), value(C, '3')];
  return {
    key,
    state: 'DIFFERENT',
    stateWord: 'different',
    classification,
    drift: classification === 'DRIFT',
    values,
    majority: null,
    outliers: [],
    valueGroups: values.map((v) => ({ value: v.value ?? '', nodes: [v] })),
  };
}

/** Three nodes: one drifting value, one setting broker-3 lacks, an expected difference and a counter. */
export function diff(over: Partial<ConfigDiffView> = {}): ConfigDiffView {
  return {
    clusterId: 'c1',
    nodes: [node(A), node(B), node(C)],
    comparable: true,
    sections: [
      {
        section: 'broker',
        label: 'Broker',
        keys: [
          outlier('/MaxDiskUsage', '90', C, '80'),
          split('/Name', 'EXPECTED'),
          outlier('/TotalMessageCount', '4', B, '9', 'UNCLASSIFIED'),
          same('/JournalType', 'NIO'),
        ],
      },
      {
        section: 'addressSettings',
        label: 'Address settings',
        keys: [outlier('/orders.#/maxSizeBytes', '1024', C, null)],
      },
    ],
    summary: { driftKeys: 2, driftNodes: 1, expectedKeys: 1 },
    matchesCompared: 1,
    matchesAvailable: 1,
    notes: [],
    ...over,
  };
}

/** Nodes that differ only where they should. */
export function cleanDiff(): ConfigDiffView {
  return diff({
    sections: [{ section: 'broker', label: 'Broker', keys: [split('/Name', 'EXPECTED'), same('/JournalType', 'NIO')] }],
    summary: { driftKeys: 0, driftNodes: 0, expectedKeys: 1 },
  });
}
