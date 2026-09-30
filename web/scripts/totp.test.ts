import { expect, it } from 'vitest';

import { totpAt } from './totp.ts';

// RFC 6238 appendix B, SHA-1: the ASCII secret "12345678901234567890", whose 8-digit codes end in these six.
const SECRET = 'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ';

it.each([
  [59, '287082'],
  [1111111109, '081804'],
  [1234567890, '005924'],
  [2000000000, '279037'],
])('gives the RFC 6238 code at %i seconds', (seconds, code) => {
  expect(totpAt(SECRET, Math.floor(seconds / 30))).toBe(code);
});
