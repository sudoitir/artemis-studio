import { describe, expect, it } from 'vitest';

import { rateLabel, rateSortValue, rateSourceLabel, totalRateLabel } from './flowFormat.ts';
import { flowQueryString } from './api.ts';
import { parseFocus, validateFlowSearch } from './flowSearch.ts';

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

describe('flow search', () => {
  it('keeps only valid, non-default values', () => {
    expect(
      validateFlowSearch({ focus: 'queue:ORDERS.inbound', rank: 'IN', limit: '100', groupBy: 'HOST', hops: '9', sort: 'bad sort' }),
    ).toEqual({ focus: 'queue:ORDERS.inbound', limit: 100, groupBy: 'HOST' });
    expect(validateFlowSearch({ focus: 'orders' })).toEqual({});
  });

  it('parses a focus with a colon in its name', () => {
    expect(parseFocus('address:jms.topic:prices')).toEqual({ kind: 'address', name: 'jms.topic:prices' });
  });

  it('always sends the bound and ranking the server should apply', () => {
    expect(flowQueryString({})).toBe('rank=IN&limit=40&groupBy=CLIENT_ID');
  });
});
