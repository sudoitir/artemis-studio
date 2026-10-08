import { describe, expect, it } from 'vitest';

import { effectLabel, expiryMark, remainingLabel, signedInWith } from './words.ts';

const MIN = 60_000;

describe('remainingLabel', () => {
  it('counts to the second in the last minute and floors, never claiming more time than there is', () => {
    expect(remainingLabel(42_900)).toBe('42s');
    expect(remainingLabel(4 * MIN + 9_000)).toBe('4m 09s');
    expect(remainingLabel(65 * MIN)).toBe('1h 05m');
    expect(remainingLabel(51 * 60 * MIN)).toBe('2d 3h');
    expect(remainingLabel(-5_000)).toBe('0s');
  });
});

describe('expiryMark', () => {
  it('names a mark only once one is reached, so a screen reader hears a few lines, not a tick', () => {
    expect(expiryMark(3 * 60 * MIN)).toBeUndefined();
    expect(expiryMark(59 * MIN)).toBe('Less than 60 minutes before the request expires.');
    expect(expiryMark(59 * MIN)).toBe(expiryMark(16 * MIN));
    expect(expiryMark(4 * MIN)).toBe('Less than 5 minutes before the request expires.');
    expect(expiryMark(30_000)).toBe('Less than a minute before the request expires.');
    expect(expiryMark(0)).toBe('The request has expired.');
  });
});

describe('effectLabel', () => {
  it('writes the count with separators and its unit', () => {
    expect(effectLabel({ count: 1204, unit: 'messages' })).toBe('1,204 messages');
  });
});

describe('signedInWith', () => {
  it('names the API token the requester used, and only says how they signed in otherwise', () => {
    expect(signedInWith('TOKEN', 'ci-deploy')).toBe('API token ci-deploy');
    expect(signedInWith('AGENT', 'assistant')).toBe('Assistant, through API token assistant');
    expect(signedInWith('SESSION', null)).toBe('Signed-in session');
    expect(signedInWith('TOKEN', null)).toBe('API token');
  });
});
