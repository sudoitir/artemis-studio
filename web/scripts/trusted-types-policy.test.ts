import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

import { TRUSTED_TYPES_DIRECTIVES } from '../src/test/trustedTypesPolicy.ts';

const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');

/**
 * The browser tests are served with Studio's Trusted Types directives (ADR-0168). They are only worth
 * something while they are the ones the server sends, and while the page makes exactly the policies
 * they name.
 */
describe('the Trusted Types policy', () => {
  it('is the one the server sends', () => {
    const server = read(
      '../../src/main/java/io/github/sudoitir/artemisstudio/kernel/security/internal/SecurityConfig.java',
    );

    expect(server).toContain(`"${TRUSTED_TYPES_DIRECTIVES}"`);
  });

  it('names the policies the page makes, and no others', () => {
    const named = TRUSTED_TYPES_DIRECTIVES.split('trusted-types ')[1].split(' ').sort();
    const made = [...read('../src/ui/trustedTypes.ts').matchAll(/createPolicy\(\s*'([^']+)'/g)].map((m) => m[1]);

    // DOMPurify makes its own; the other two are made in trustedTypes.ts.
    expect(named).toEqual([...made, 'dompurify'].sort());
  });
});
