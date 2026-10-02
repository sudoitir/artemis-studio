import { describe, expect, it } from 'vitest';

import { validateTopologySearch } from './topologySearch.ts';

describe('validateTopologySearch', () => {
  it('keeps a chosen node, the table view and a sort', () => {
    expect(validateTopologySearch({ node: 'a2', view: 'table', sort: '-seen' })).toEqual({
      node: 'a2',
      view: 'table',
      sort: '-seen',
    });
  });

  it('leaves the graph out, since it is the default', () => {
    expect(validateTopologySearch({ view: 'graph' })).toEqual({});
  });

  it('drops values of the wrong shape', () => {
    expect(validateTopologySearch({ node: '', view: 'split', sort: 'a b' })).toEqual({});
    expect(validateTopologySearch({ node: 3, sort: 4 })).toEqual({});
  });
});
