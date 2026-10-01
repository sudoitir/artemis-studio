import { describe, expect, it } from 'vitest';

import { expiryNote, licenseAdvice, licenseNeedsAction, LICENSE_LABEL } from './words.ts';

const DAY = 24 * 60 * 60 * 1000;
const NOW = Date.parse('2026-10-01T12:00:00Z');
const at = (offset: number) => new Date(NOW + offset).toISOString();

describe('expiryNote', () => {
  it('counts days ahead, and says today and tomorrow', () => {
    expect(expiryNote(at(30.5 * DAY), NOW)).toBe('in 30 days');
    expect(expiryNote(at(2 * DAY), NOW)).toBe('in 2 days');
    expect(expiryNote(at(1.5 * DAY), NOW)).toBe('tomorrow');
    expect(expiryNote(at(3 * 60 * 60 * 1000), NOW)).toBe('today');
  });

  it('counts days gone, and says yesterday and today', () => {
    expect(expiryNote(at(-3.5 * DAY), NOW)).toBe('3 days ago');
    expect(expiryNote(at(-1.5 * DAY), NOW)).toBe('yesterday');
    expect(expiryNote(at(-60 * 1000), NOW)).toBe('today');
  });
});

describe('licenses in words', () => {
  it('has a label and advice for every state, and only a valid one needs nothing', () => {
    for (const state of Object.keys(LICENSE_LABEL) as (keyof typeof LICENSE_LABEL)[]) {
      expect(LICENSE_LABEL[state]).not.toBe('');
      expect(licenseAdvice(state, 'Notes', 'Acme')).toContain(state === 'EXPIRED' ? 'license' : 'Notes');
    }
    expect(licenseNeedsAction(null)).toBe(false);
    expect(licenseNeedsAction({ state: 'VALID' } as never)).toBe(false);
    for (const state of ['MISSING', 'UNCHECKED', 'EXPIRING', 'EXPIRED', 'OVER_LIMIT', 'INVALID']) {
      expect(licenseNeedsAction({ state } as never)).toBe(true);
    }
  });
});
