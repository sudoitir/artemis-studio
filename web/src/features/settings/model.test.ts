import { describe, expect, it } from 'vitest';

import { besideField, boundsText } from './model.ts';

describe('besideField', () => {
  it('drops the key the server names and makes a sentence of the rest', () => {
    expect(besideField('gate.max-hold', 'gate.max-hold must be between 1m and 365d')).toBe(
      'Must be between 1m and 365d.',
    );
  });

  it('leaves a message that does not start with the key as it is', () => {
    expect(besideField('gate.max-hold', 'Not a duration.')).toBe('Not a duration.');
  });
});

describe('boundsText', () => {
  it('says the range a whole number takes', () => {
    expect(boundsText({ kind: 'INT', min: '1', max: null })).toBe('A whole number, at least 1.');
    expect(boundsText({ kind: 'INT', min: '10', max: '500' })).toBe('A whole number from 10 to 500.');
  });

  it('says the range a duration takes, and when forever is allowed', () => {
    expect(boundsText({ kind: 'DURATION', min: '5s', max: '24h' })).toBe('From 5s to 24h.');
    expect(boundsText({ kind: 'DURATION', min: '1h', max: 'forever' })).toBe(
      'At least 1h. Enter forever for no limit.',
    );
    expect(boundsText({ kind: 'DURATION_OR_OFF', min: null, max: '7d' })).toBe('At most 7d.');
    expect(boundsText({ kind: 'DURATION', min: null, max: null })).toBe('');
  });

  it('says nothing for kinds without bounds', () => {
    expect(boundsText({ kind: 'CRON', min: null, max: null })).toBe('');
  });
});
