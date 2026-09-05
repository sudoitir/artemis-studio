import { describe, expect, it } from 'vitest';

import { normaliseSeeds } from './normaliseSeeds.ts';

describe('normaliseSeeds', () => {
  it('turns a bare host into a full Jolokia URL', () => {
    expect(normaliseSeeds('broker-1')).toEqual([
      { original: 'broker-1', url: 'http://broker-1:8161/console/jolokia' },
    ]);
  });

  it('leaves an explicit scheme, port, and path alone', () => {
    expect(normaliseSeeds('https://broker-1:9999/custom/path')).toEqual([
      { original: 'https://broker-1:9999/custom/path', url: 'https://broker-1:9999/custom/path' },
    ]);
  });

  it('splits on newlines, commas, semicolons, and whitespace', () => {
    const result = normaliseSeeds('broker-1\nbroker-2, broker-3; broker-4 broker-5');
    expect(result.map((s) => s.original)).toEqual([
      'broker-1',
      'broker-2',
      'broker-3',
      'broker-4',
      'broker-5',
    ]);
  });

  it('dedupes identical normalised URLs', () => {
    const result = normaliseSeeds('broker-1\nhttp://broker-1:8161/console/jolokia');
    expect(result).toHaveLength(1);
  });

  it('reports a genuinely unparseable token as such, with the original text preserved', () => {
    const result = normaliseSeeds('http://');
    expect(result).toEqual([{ original: 'http://', url: null }]);
  });
});
