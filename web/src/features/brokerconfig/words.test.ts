import { describe, expect, it } from 'vitest';

import type { ConfigCatalogueView, ConfigDeclarationView, ConfigDriftFindingView, ConfigNodeStateView } from './api.ts';
import {
  appliedWords,
  applyModeWords,
  applyOutcomeWords,
  asSection,
  findingKindWords,
  findingRows,
  hazardClassWords,
  itemDriftWords,
  itemFindings,
  nodeStateWords,
  stepIdsFor,
  stepStatusWords,
  valueWords,
  wireSectionLabel,
} from './words.ts';

const finding = (over: Partial<ConfigDriftFindingView> = {}): ConfigDriftFindingView => ({
  kind: 'DIVERGENT',
  section: 'ADDRESS',
  key: 'orders',
  detail: '',
  declared: {},
  observed: {},
  ...over,
});

const node = (over: Partial<ConfigNodeStateView> = {}): ConfigNodeStateView => ({
  nodeId: '00000000-0000-0000-0000-000000000001',
  nodeName: 'n1',
  live: true,
  state: 'IN_SYNC',
  findings: [],
  ...over,
});

const declaration = (nodes: ConfigNodeStateView[], over: Partial<ConfigDeclarationView> = {}): ConfigDeclarationView =>
  ({ declared: true, revision: 3, applyMode: 'STUDIO_MANAGED', nodes, ...over }) as ConfigDeclarationView;

describe('sections', () => {
  it('accepts only the five section names', () => {
    expect(asSection('bridges')).toBe('bridges');
    expect(asSection('nope')).toBeUndefined();
    expect(asSection(7)).toBeUndefined();
  });

  it('labels every wire section, falling back to a readable form', () => {
    expect(
      ['ADDRESS', 'QUEUE', 'ADDRESS_SETTING', 'SECURITY_SETTING', 'DIVERT', 'BRIDGE'].map(wireSectionLabel),
    ).toEqual(['address', 'queue', 'address setting', 'security setting', 'divert', 'bridge']);
    expect(wireSectionLabel('HTTP_ACCEPTOR')).toBe('http acceptor');
    expect(wireSectionLabel(null)).toBe('');
    expect(wireSectionLabel(undefined)).toBe('');
  });
});

describe('state words', () => {
  it('states every node state in words and marks only the worrying ones', () => {
    expect(nodeStateWords('IN_SYNC')).toEqual({ text: 'in sync' });
    expect(nodeStateWords('DRIFTED')).toEqual({ text: 'drifted', tone: 'warning' });
    expect(nodeStateWords('NOT_EVALUATED')).toEqual({ text: 'not evaluated' });
    expect(nodeStateWords('UNREACHABLE')).toEqual({ text: 'unreachable — not evaluated', tone: 'warning' });
    expect(nodeStateWords('MYSTERY' as ConfigNodeStateView['state'])).toEqual({ text: 'MYSTERY' });
  });

  it('words every finding kind and humanises an unknown one', () => {
    expect(findingKindWords('MISSING')).toBe('Missing');
    expect(findingKindWords('DIVERGENT')).toBe('Differs');
    expect(findingKindWords('UNDECLARED')).toBe('Undeclared');
    expect(findingKindWords('DIVERGENT_QUEUE')).toMatch(/^Queue differs/);
    expect(findingKindWords('DIVERGENT_ADDRESS')).toMatch(/^Address keeps a routing type/);
    expect(findingKindWords('NOT_EVALUATED')).toBe('Not evaluated');
    expect(findingKindWords('UNVERIFIABLE')).toBe('Cannot be verified');
    expect(findingKindWords('NOT_CONNECTED')).toMatch(/a fault, not drift$/);
    expect(findingKindWords('SOME_NEW_KIND')).toBe('some new kind');
  });

  it('words hazard classes, apply outcomes and apply modes', () => {
    expect(['HIGH', 'MEDIUM', 'LOW'].map((c) => hazardClassWords(c as never))).toEqual(['High', 'Medium', 'Low']);
    expect(hazardClassWords('EXTREME' as never)).toBe('EXTREME');
    expect(applyOutcomeWords('DRY_RUN')).toEqual({ text: 'preview' });
    expect(applyOutcomeWords('APPLIED')).toEqual({ text: 'applied' });
    expect(applyOutcomeWords('HALTED')).toEqual({ text: 'halted', tone: 'warning' });
    expect(applyOutcomeWords('FAILED')).toEqual({ text: 'failed', tone: 'danger' });
    expect(applyOutcomeWords('OTHER' as never)).toEqual({ text: 'OTHER' });
    expect(applyModeWords('CONFIG_MANAGED')).toBe('Managed outside Studio');
    expect(applyModeWords('STUDIO_MANAGED')).toBe('Managed by Studio');
  });

  it('words a plan step by status, and an applied step by how it was read back', () => {
    const step = (status: string, verified = 'NOT_VERIFIED') => stepStatusWords({ status, verified } as never);
    expect(step('WOULD_APPLY')).toEqual({ text: 'would apply' });
    expect(step('APPLIED', 'VERIFIED')).toEqual({ text: 'applied and verified' });
    expect(step('APPLIED', 'MISMATCH')).toEqual({ text: 'applied — read back differs', tone: 'danger' });
    expect(step('APPLIED', 'UNVERIFIABLE')).toEqual({ text: 'applied — cannot be verified' });
    expect(step('ALREADY')).toEqual({ text: 'already as declared' });
    expect(step('FAILED')).toEqual({ text: 'failed', tone: 'danger' });
    expect(step('NOT_ATTEMPTED')).toEqual({ text: 'not attempted', tone: 'warning' });
    expect(step('SKIPPED_NOT_LIVE')).toEqual({ text: 'skipped — not live', tone: 'warning' });
    expect(step('NEW')).toEqual({ text: 'NEW' });
  });
});

describe('itemDriftWords', () => {
  it('says so when no node is live or none was evaluated', () => {
    expect(itemDriftWords(declaration([node({ live: false })]), 'addresses', 'orders')).toEqual({
      text: 'no live node',
    });
    expect(itemDriftWords(declaration([node({ state: 'UNREACHABLE' })]), 'addresses', 'orders')).toEqual({
      text: 'not evaluated',
    });
  });

  it('reads in sync, and notes the live nodes that were not evaluated', () => {
    const d = declaration([node(), node({ nodeName: 'n2', state: 'NOT_EVALUATED' })]);
    expect(itemDriftWords(d, 'addresses', 'orders')).toEqual({ text: 'in sync on 1/2 (1 not evaluated)' });
    expect(itemDriftWords(declaration([node(), node({ nodeName: 'n2' })]), 'addresses', 'orders')).toEqual({
      text: 'in sync on 2/2',
    });
  });

  it('groups missing and differing findings by node, sorted and deduplicated', () => {
    const d = declaration([
      node({ nodeName: 'n1', state: 'DRIFTED', findings: [finding({ kind: 'MISSING' })] }),
      node({
        nodeName: 'n2',
        state: 'DRIFTED',
        findings: [finding({ kind: 'DIVERGENT' }), finding({ kind: 'DIVERGENT_ADDRESS' })],
      }),
      node({ nodeName: 'n3', state: 'DRIFTED', findings: [finding({ kind: 'MISSING' })] }),
    ]);
    expect(itemDriftWords(d, 'addresses', 'orders')).toEqual({
      text: 'missing on n1, n3; differs on n2',
      tone: 'warning',
    });
  });

  it('ignores undeclared findings, findings of other sections and other keys', () => {
    const d = declaration([
      node({
        state: 'DRIFTED',
        findings: [
          finding({ kind: 'UNDECLARED' }),
          finding({ section: 'DIVERT' }),
          finding({ key: 'other' }),
          finding({ section: 'QUEUE', key: null }),
        ],
      }),
    ]);
    expect(itemDriftWords(d, 'addresses', 'orders')).toEqual({ text: 'in sync on 1/1' });
  });

  it('names a queue finding on its address row when the queue is declared on it', () => {
    const d = declaration([
      node({ state: 'DRIFTED', findings: [finding({ section: 'QUEUE', key: 'orders.q', kind: 'DIVERGENT_QUEUE' })] }),
    ]);
    expect(itemDriftWords(d, 'addresses', 'orders', ['orders.q'])).toEqual({
      text: 'queue orders.q differs on n1',
      tone: 'warning',
    });
    expect(itemDriftWords(d, 'addresses', 'orders', [])).toEqual({ text: 'in sync on 1/1' });
  });

  it('reports a bridge that is not forwarding as a fault beside sync, and beside drift', () => {
    const faulted = finding({ section: 'BRIDGE', key: 'b1', kind: 'NOT_CONNECTED' });
    expect(itemDriftWords(declaration([node({ findings: [faulted] })]), 'bridges', 'b1')).toEqual({
      text: 'in sync on 1/1; as declared but not forwarding on n1',
      tone: 'warning',
    });
    const d = declaration([
      node({ state: 'DRIFTED', findings: [finding({ section: 'BRIDGE', key: 'b1', kind: 'MISSING' }), faulted] }),
    ]);
    expect(itemDriftWords(d, 'bridges', 'b1')).toEqual({
      text: 'missing on n1; as declared but not forwarding on n1',
      tone: 'warning',
    });
  });
});

describe('itemFindings and stepIdsFor', () => {
  it('lists the live nodes findings about an item with their labels', () => {
    const own = finding();
    const queue = finding({ section: 'QUEUE', key: 'orders.q' });
    const d = declaration([
      node({ findings: [own, queue, finding({ kind: 'UNDECLARED' }), finding({ key: 'other' })] }),
      node({ nodeName: 'n2', live: false, findings: [own] }),
    ]);
    expect(itemFindings(d, 'addresses', 'orders', ['orders.q'])).toEqual([
      { nodeName: 'n1', label: '', finding: own },
      { nodeName: 'n1', label: 'queue orders.q ', finding: queue },
    ]);
  });

  it('scopes an apply to an item with every op', () => {
    expect(stepIdsFor([{ section: 'DIVERT', key: 'd1' }])).toEqual([
      'DIVERT:d1:ADD',
      'DIVERT:d1:REPLACE',
      'DIVERT:d1:REMOVE',
    ]);
    expect(stepIdsFor([])).toEqual([]);
  });
});

describe('appliedWords', () => {
  it('says nothing is declared before the first revision', () => {
    expect(appliedWords(declaration([], { declared: false }))).toEqual({
      text: 'Nothing is declared for this cluster yet',
    });
  });

  it('warns when no node is live', () => {
    expect(appliedWords(declaration([node({ live: false })]))).toEqual({
      text: 'Revision 3 — no node is live, so nothing could be applied or compared',
      tone: 'warning',
    });
  });

  it('counts only live nodes verified at this revision', () => {
    const part = appliedWords(
      declaration([node({ verifiedRevision: 3 }), node({ nodeName: 'n2', verifiedRevision: 2 })]),
    );
    expect(part).toEqual({ text: 'Revision 3 — applied to 1 of 2 live nodes', tone: 'warning' });
    const all = appliedWords(declaration([node({ verifiedRevision: 3 })]));
    expect(all).toEqual({ text: 'Revision 3 — applied to 1 of 1 live node', tone: undefined });
  });
});

describe('valueWords', () => {
  it('reads scalars as themselves, lists joined, objects as JSON and blanks as a dash', () => {
    expect(valueWords(null)).toBe('—');
    expect(valueWords(undefined)).toBe('—');
    expect(valueWords('x')).toBe('x');
    expect(valueWords(5)).toBe('5');
    expect(valueWords(false)).toBe('false');
    expect(valueWords(['a', 2, null])).toBe('a, 2, —');
    expect(valueWords({ a: 1 })).toBe('{"a":1}');
  });
});

describe('findingRows', () => {
  it('lines up declared beside observed for every key, sorted, flagging differences', () => {
    expect(findingRows(finding({ declared: { b: 1, a: 'x' }, observed: { a: 'x', c: true } }))).toEqual([
      { key: 'a', declared: 'x', observed: 'x', differs: false },
      { key: 'b', declared: '1', observed: '—', differs: true },
      { key: 'c', declared: '—', observed: 'true', differs: true },
    ]);
  });

  it('reads address settings with units and the broker.xml key name', () => {
    const catalogue = {
      addressSettingKeys: [{ jsonName: 'maxSizeBytes', xmlName: 'max-size-bytes' }],
    } as ConfigCatalogueView;
    expect(
      findingRows(
        finding({ section: 'ADDRESS_SETTING', declared: { maxSizeBytes: 2048 }, observed: { maxSizeBytes: -1 } }),
        catalogue,
      ),
    ).toEqual([{ key: 'max-size-bytes', declared: '2,048 (2 KiB)', observed: '-1 (no limit)', differs: true }]);
  });

  it('is empty when a finding compares nothing', () => {
    expect(findingRows(finding({ declared: undefined as never, observed: undefined as never }))).toEqual([]);
  });
});
