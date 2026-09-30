import { describe, expect, it } from 'vitest';

import { environmentMarkdown, issueBody, issueUrl, MAX_ISSUE_URL } from './bugReport.ts';

const summary = {
  studioVersion: '2026.09.4',
  contractVersion: 6,
  java: '25.0.4+7 (Eclipse Adoptium)',
  os: 'Linux 6.8 (amd64)',
  database: 'PostgreSQL 17.2',
};

describe('bug report', () => {
  it('lists the environment, and says unknown rather than dropping a row', () => {
    const md = environmentMarkdown({
      summary: undefined,
      signIn: [],
      plugins: [{ id: 'acme-x', version: '1.2.0', status: 'ACTIVE' }],
      browser: 'Firefox | 140',
    });
    expect(md).toContain('| Studio | unknown |');
    expect(md).toContain('| Plugins | acme-x 1.2.0 (active) |');
    expect(md).toContain('| Browser | Firefox \\| 140 |');
  });

  it('puts the description before the environment and marks empty parts', () => {
    const body = issueBody(
      { happened: 'It froze', expected: '', steps: '1. open' },
      environmentMarkdown({
        summary,
        signIn: ['Local'],
        plugins: [],
        browser: 'x',
      }),
    );
    expect(body.indexOf('It froze')).toBeLessThan(body.indexOf('### Environment'));
    expect(body).toContain('### What I expected\n\n_Not given._');
    expect(body).toContain('| Studio | 2026.09.4 (plugin contract 6) |');
  });

  it('keeps the body in the link while it fits, and leaves it out when it does not', () => {
    const short = issueUrl('https://example.test/repo', 'Crash', 'body text');
    expect(short.bodyInUrl).toBe(true);
    expect(new URL(short.url).searchParams.get('body')).toBe('body text');

    const long = issueUrl('https://example.test/repo', 'Crash', 'x'.repeat(MAX_ISSUE_URL));
    expect(long.bodyInUrl).toBe(false);
    expect(new URL(long.url).searchParams.get('body')).toBeNull();
    expect(new URL(long.url).searchParams.get('title')).toBe('Crash');
  });
});
