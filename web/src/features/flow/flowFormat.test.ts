import { describe, expect, it } from 'vitest';

import { edgeText, rateLabel, rateSortValue, rateSourceLabel, totalRateLabel } from './flowFormat.ts';
import { flowQueryString } from './api.ts';
import { layersParam, parseFocus, parseLayers, validateFlowSearch } from './flowSearch.ts';

describe('flow formatting', () => {
  it('never shows an unknown rate as zero', () => {
    expect(rateLabel({ rate: undefined, rateSource: 'SAMPLER', kind: 'CONSUME' })).toBe('measuring…');
    expect(rateLabel({ rate: 0, rateSource: 'SAMPLER', kind: 'CONSUME' })).toBe('0 msg/s');
    expect(rateLabel({ rate: undefined, rateSource: 'NONE', kind: 'ROUTE' })).toBe('not counted by broker');
    expect(totalRateLabel(null)).toBe('measuring…');
  });

  it('names a slow-tier rate as an average', () => {
    expect(rateSourceLabel({ rateSource: 'QUEUE_METRIC', averagedOverSeconds: 300 })).toBe(
      'queue metrics, ~5 min average',
    );
  });

  it('sorts unknown rates after known ones in either direction', () => {
    const rates = [3, undefined, 0, 7];
    const desc = [...rates].sort((a, b) => rateSortValue(b, true) - rateSortValue(a, true));
    const asc = [...rates].sort((a, b) => rateSortValue(a, false) - rateSortValue(b, false));
    expect(desc).toEqual([7, 3, 0, undefined]);
    expect(asc).toEqual([0, 3, 7, undefined]);
  });
});

describe('routing edge text', () => {
  it('says what a divert does, where it is missing, and that the broker does not count it', () => {
    expect(
      edgeText({
        kind: 'DIVERT',
        exclusive: true,
        filter: "color='red'",
        rateSource: 'NONE',
        stale: false,
        bypassed: false,
        studio: false,
        presentOn: 1,
        presentOf: 2,
        faults: ['PARTIAL_PRESENCE'],
      }),
    ).toBe('reroutes · filtered · not counted by broker · on 1 of 2 nodes');
  });

  it('names a bridge that is down, a bypassed route and a wildcard match in words', () => {
    expect(
      edgeText({ kind: 'BRIDGE', rate: 5, rateSource: 'SAMPLER', stale: false, bypassed: false, studio: false, faults: ['BRIDGE_DOWN'] }),
    ).toBe('bridge · 5 msg/s · not connected');
    expect(
      edgeText({ kind: 'ROUTE', delivery: 'SHARED', rateSource: 'QUEUE_METRIC', rate: 2, stale: false, bypassed: true, studio: false, faults: [] }),
    ).toBe('shared · bypassed by an exclusive divert · 2 msg/s');
    expect(edgeText({ kind: 'WILDCARD', rateSource: 'NONE', stale: false, bypassed: false, studio: false, faults: [] })).toBe('matches');
  });
});

describe('flow search', () => {
  it('keeps only valid, non-default values', () => {
    expect(
      validateFlowSearch({ focus: 'queue:ORDERS.inbound', rank: 'IN', limit: '100', groupBy: 'HOST', hops: '9', sort: 'bad sort' }),
    ).toEqual({ focus: 'queue:ORDERS.inbound', limit: 100, groupBy: 'HOST' });
    expect(validateFlowSearch({ focus: 'orders' })).toEqual({});
  });

  it('keeps the default layers out of the URL and says NONE for no layers', () => {
    expect(parseLayers(undefined)).toEqual(['BRIDGES', 'CLUSTER', 'DIVERTS']);
    expect(layersParam(['DIVERTS', 'CLUSTER', 'BRIDGES'])).toBeUndefined();
    expect(layersParam([])).toBe('NONE');
    expect(parseLayers('NONE')).toEqual([]);
    expect(validateFlowSearch({ layers: 'dead_letter,diverts,bogus' })).toEqual({ layers: 'DEAD_LETTER,DIVERTS' });
  });

  it('parses a focus with a colon in its name', () => {
    expect(parseFocus('address:jms.topic:prices')).toEqual({ kind: 'address', name: 'jms.topic:prices' });
  });

  it('always sends the bound and ranking the server should apply', () => {
    expect(flowQueryString({})).toBe('rank=IN&limit=40&groupBy=CLIENT_ID');
  });

  it('asks for the per-node breakdown only in the Split layout', () => {
    expect(flowQueryString({ tab: 'table' })).not.toContain('byNode');
    expect(flowQueryString({ tab: 'split' })).toContain('byNode=true');
  });

  it('keeps the layout, the selection and a non-default range', () => {
    expect(validateFlowSearch({ tab: 'split', node: 'queue:orders', range: '6h' })).toEqual({
      tab: 'split',
      node: 'queue:orders',
      range: '6h',
    });
    expect(validateFlowSearch({ tab: 'graph', range: '1h', node: '' })).toEqual({});
  });
});
