import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { axeViolations, Frame, renderThemed, SCHEMES } from '../test/browser.tsx';
import { StatusBadge } from './StatusBadge.tsx';

const TONES = ['neutral', 'info', 'warning', 'danger'] as const;

const badges = (
  <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
    {TONES.map((tone) => (
      <StatusBadge key={tone} tone={tone}>
        {`State ${tone}`}
      </StatusBadge>
    ))}
  </div>
);

describe('StatusBadge', () => {
  describe.each(SCHEMES)('in the %s scheme', (scheme) => {
    it('has no accessibility violations in any tone', async () => {
      const { container } = renderThemed(<Frame width={960}>{badges}</Frame>, scheme);
      expect(await axeViolations(container)).toEqual([]);
    });
  });

  it('is a hairline-outlined box around its word, as tall as its line and one spacing step wide on each side', () => {
    renderThemed(<Frame width={960}>{badges}</Frame>, 'light');
    const badge = screen.getByText('State neutral');
    const style = getComputedStyle(badge);
    expect(Number.parseFloat(style.borderTopWidth)).toBe(1);
    expect(Number.parseFloat(style.paddingInlineStart)).toBeGreaterThan(0);
    const box = badge.getBoundingClientRect();
    const range = document.createRange();
    range.selectNodeContents(badge);
    const text = range.getBoundingClientRect();
    expect(box.width).toBeCloseTo(text.width + 2 * Number.parseFloat(style.paddingInlineStart) + 2, 0);
    expect(box.height).toBeGreaterThan(text.height);
  });

  it('breaks a word wider than its container instead of overflowing it', () => {
    renderThemed(
      <Frame width={200}>
        <StatusBadge>{'x'.repeat(120)}</StatusBadge>
      </Frame>,
      'light',
    );
    const box = screen.getByText('x'.repeat(120)).getBoundingClientRect();
    expect(box.width).toBeLessThanOrEqual(200);
  });
});
