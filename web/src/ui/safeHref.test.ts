import { describe, expect, it } from 'vitest';

import { safeHref } from './safeHref.ts';

describe('safeHref', () => {
  it.each([
    'https://example.com/docs',
    'http://example.com',
    'mailto:ops@example.com',
    '/clusters/abc/queues?q=x',
    'relative/path',
    '#main',
    `${globalThis.location.origin}/api/v1/auth/oidc/start`,
  ])('keeps %s', (value) => {
    expect(safeHref(value)).toBe(value);
  });

  it.each([
    'javascript:alert(1)',
    'JaVaScRiPt:alert(1)',
    '  javascript:alert(1)',
    'java\tscript:alert(1)',
    'java\nscript:alert(1)',
    '\u0001javascript:alert(1)',
    'data:text/html,<script>alert(1)</script>',
    'vbscript:msgbox(1)',
    'blob:https://example.com/1234',
    'file:///etc/passwd',
    'ftp://example.com/x',
  ])('refuses %j', (value) => {
    expect(safeHref(value)).toBeUndefined();
  });

  it('has nothing to link when there is no value', () => {
    expect(safeHref(undefined)).toBeUndefined();
    expect(safeHref(null)).toBeUndefined();
    expect(safeHref('')).toBeUndefined();
  });
});
