import { describe, expect, it } from 'vitest';

import { plural } from './plural.ts';

describe('plural', () => {
  it.each([
    ['address', 'addresses'],
    ['consumer', 'consumers'],
    ['session', 'sessions'],
    ['connection', 'connections'],
    ['producer', 'producers'],
  ])('writes %s as %s', (noun, expected) => {
    expect(plural(noun)).toBe(expected);
  });
});
