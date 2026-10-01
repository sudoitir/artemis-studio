import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { axeViolations, Frame, renderThemed, SCHEMES } from '../test/browser.tsx';
import { Notice } from './Notice.tsx';

const TONES = ['neutral', 'info', 'warning', 'danger'] as const;

const notices = (
  <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
    {TONES.map((tone) => (
      <Notice key={tone} tone={tone} title={`Title ${tone}`} action={<button type="button">Act on {tone}</button>}>
        What {tone} means and what to do about it.
      </Notice>
    ))}
  </div>
);

describe('Notice', () => {
  describe.each(SCHEMES)('in the %s scheme', (scheme) => {
    it('has no accessibility violations in any tone', async () => {
      const { container } = renderThemed(<Frame width={960}>{notices}</Frame>, scheme);
      expect(await axeViolations(container)).toEqual([]);
    });
  });

  it('breaks a word wider than its container instead of overflowing it', () => {
    renderThemed(
      <Frame width={240}>
        <Notice title="Long value">{'x'.repeat(120)}</Notice>
      </Frame>,
      'light',
    );
    const notice = screen.getByRole('status');
    expect(notice.scrollWidth).toBeLessThanOrEqual(notice.clientWidth);
    expect(notice.getBoundingClientRect().width).toBeLessThanOrEqual(240);
  });
});
