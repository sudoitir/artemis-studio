import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { runInNewContext } from 'node:vm';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

const SOURCE = readFileSync(resolve(process.cwd(), 'public/boot-prefs.js'), 'utf8');
const originalMatchMedia = window.matchMedia;

/** What the browser does before React: runs the static script against this page. */
function bootPrefs(systemIsDark = false) {
  window.matchMedia = (query: string) =>
    ({ ...originalMatchMedia(query), matches: systemIsDark && query.includes('dark') }) as MediaQueryList;
  runInNewContext(SOURCE, { window, document });
  return {
    scheme: document.documentElement.getAttribute('data-mantine-color-scheme'),
    density: document.documentElement.getAttribute('data-density'),
  };
}

describe('boot-prefs.js', () => {
  beforeEach(() => window.localStorage.clear());
  afterEach(() => {
    window.matchMedia = originalMatchMedia;
  });

  it.each([
    ['light', true, 'light'],
    ['dark', false, 'dark'],
    ['auto', true, 'dark'],
    ['auto', false, 'light'],
  ])('with the stored scheme %s and a dark system %s sets %s', (stored, systemIsDark, expected) => {
    window.localStorage.setItem('mantine-color-scheme-value', stored);

    expect(bootPrefs(systemIsDark).scheme).toBe(expected);
  });

  it('follows the system on a first visit', () => {
    expect(bootPrefs(true).scheme).toBe('dark');
    expect(bootPrefs(false).scheme).toBe('light');
  });

  it('ignores a scheme outside its three values', () => {
    window.localStorage.setItem('mantine-color-scheme-value', '<img onerror=x>');

    expect(bootPrefs(true).scheme).toBe('dark');
  });

  it('sets the stored density and defaults to compact', () => {
    expect(bootPrefs().density).toBe('compact');

    window.localStorage.setItem('as:density', JSON.stringify('comfortable'));
    expect(bootPrefs().density).toBe('comfortable');
  });

  it.each(['spacious', 'not json', '{"a":1}'])('ignores the stored density %s', (stored) => {
    window.localStorage.setItem('as:density', stored);

    expect(bootPrefs().density).toBe('compact');
  });
});
