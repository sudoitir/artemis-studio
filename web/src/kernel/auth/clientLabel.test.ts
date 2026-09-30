import { describe, expect, it } from 'vitest';

import { describeBrowser, describeClient } from './clientLabel.ts';

describe('describeClient', () => {
  it.each([
    ['Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0', 'Firefox on Linux'],
    [
      'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36',
      'Chrome on Windows',
    ],
    [
      'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36 Edg/129.0.0.0',
      'Edge on Windows',
    ],
    [
      'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15',
      'Safari on macOS',
    ],
    [
      'Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1',
      'Safari on iOS',
    ],
    [
      'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36',
      'Chrome on Android',
    ],
  ])('summarises %s', (ua, label) => {
    expect(describeClient(ua)).toBe(label);
  });

  it('shows a tool by its own name and a missing agent as unknown', () => {
    expect(describeClient('curl/8.5.0')).toBe('curl/8.5.0');
    expect(describeClient(null)).toBe('Unknown client');
    expect(describeClient('   ')).toBe('Unknown client');
  });

  it('cuts a very long token', () => {
    expect(describeClient('x'.repeat(100))).toBe(`${'x'.repeat(40)}…`);
  });
});

describe('describeBrowser', () => {
  it('names a browser and refuses to guess for anything else', () => {
    expect(describeBrowser('Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0')).toBe(
      'Firefox on Linux',
    );
    expect(describeBrowser('curl/8.5.0')).toBeNull();
  });
});
