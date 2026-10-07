import { describe, expect, it } from 'vitest';

import { A, B, C, cleanDiff, diff, node, outlier, same, split } from './configDiffFixtures.ts';
import { diffRows, filterRows, outlierWords, summaryWords } from './configDiffRows.ts';

const rows = diffRows(diff());
const keys = (query: Partial<Parameters<typeof filterRows>[1]>) =>
  filterRows(rows, { filter: 'all', text: '', nodes: [], ...query }).map((r) => r.key);

describe('filterRows', () => {
  it('lists drift, expected differences and every key apart', () => {
    expect(keys({ filter: 'drift' })).toEqual(['/MaxDiskUsage', '/orders.#/maxSizeBytes']);
    expect(keys({ filter: 'expected' })).toEqual(['/Name']);
    expect(keys({ filter: 'all' })).toHaveLength(5);
  });

  it('matches a key, a section or any node value, ignoring case', () => {
    expect(keys({ text: 'maxdisk' })).toEqual(['/MaxDiskUsage']);
    expect(keys({ text: 'address settings' })).toEqual(['/orders.#/maxSizeBytes']);
    expect(keys({ text: ' 1024 ' })).toEqual(['/orders.#/maxSizeBytes']);
  });

  it('keeps the keys that differ on a node: its outliers, or every node when no value is the majority', () => {
    expect(keys({ nodes: ['n-c'] })).toEqual(['/MaxDiskUsage', '/Name', '/orders.#/maxSizeBytes']);
    expect(keys({ nodes: ['n-b'] })).toEqual(['/Name', '/TotalMessageCount']);
    expect(keys({ nodes: ['n-a', 'n-b'] })).toEqual(['/Name', '/TotalMessageCount']);
  });
});

describe('outlierWords', () => {
  it('says who differs, who is missing the key, and nothing for a key every node agrees on', () => {
    expect(outlierWords(outlier('/k', '1', C, '2'))).toBe('broker-3 differs: 2');
    expect(outlierWords(outlier('/k', '1', B, null))).toBe('broker-2 missing');
    expect(outlierWords(split('/k'))).toBe('1 on broker-1 · 2 on broker-2 · 3 on broker-3');
    expect(outlierWords(same('/k', '1'))).toBe('');
  });
});

describe('summaryWords', () => {
  it('counts keys and nodes, singular and plural', () => {
    expect(summaryWords(diff({ summary: { driftKeys: 1, driftNodes: 1, expectedKeys: 0 } }))).toBe(
      '1 key drifts on 1 node.',
    );
    expect(summaryWords(diff({ summary: { driftKeys: 3, driftNodes: 2, expectedKeys: 4 } }))).toBe(
      '3 keys drift on 2 nodes. 4 expected differences set aside.',
    );
  });

  it('says a clean cluster is clean and what it set aside, and names a node it could not compare', () => {
    expect(summaryWords(cleanDiff())).toBe(
      'No key drifts across the 3 nodes compared. 1 expected difference set aside.',
    );
    const silent = cleanDiff();
    silent.nodes = [node(A), node(B), node(C, { available: false })];
    silent.summary = { driftKeys: 0, driftNodes: 0, expectedKeys: 0 };
    expect(summaryWords(silent)).toBe(
      'No key drifts across the 2 nodes compared. broker-3 did not answer, so it was not compared.',
    );
  });
});
