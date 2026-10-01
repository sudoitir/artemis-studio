import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { axeViolations, Frame, renderThemed, SCHEMES } from '../test/browser.tsx';
import { Stat } from './Stat.tsx';

const stats = (
  <div style={{ display: 'flex', gap: 24 }}>
    <Stat label="Depth" value="12,840" unit="messages" />
    <Stat label="Rate" value={null} unavailableReason="No consumer has reported a rate yet." />
    <Stat label="Backlog" value="0" loading />
  </div>
);

describe('Stat', () => {
  describe.each(SCHEMES)('in the %s scheme', (scheme) => {
    it('has no accessibility violations known, unavailable and loading', async () => {
      const { container } = renderThemed(<Frame width={960}>{stats}</Frame>, scheme);
      expect(await axeViolations(container)).toEqual([]);
    });
  });

  it('holds one value row height whether the figure is known, unavailable or loading', () => {
    renderThemed(<Frame width={960}>{stats}</Frame>, 'light');
    const heights = ['12,840', 'Unavailable', 'Loading'].map(
      (text) => screen.getByText(text, { exact: false }).closest('dd')!.getBoundingClientRect().height,
    );
    expect(heights[1]).toBe(heights[0]);
    expect(heights[2]).toBe(heights[0]);
  });

  it('writes the reason beneath an unavailable figure, never a zero', () => {
    renderThemed(<Frame width={960}>{stats}</Frame>, 'light');
    const figure = screen.getByText('Unavailable').closest('dd')!.getBoundingClientRect();
    const reason = screen.getByText('No consumer has reported a rate yet.').getBoundingClientRect();
    expect(reason.top).toBeGreaterThanOrEqual(figure.bottom);
    expect(screen.queryByText('0')).toBeNull();
  });

  it('puts the label above the figure and the unit after it', () => {
    renderThemed(<Frame width={960}>{stats}</Frame>, 'light');
    const label = screen.getByText('Depth').getBoundingClientRect();
    const figure = screen.getByText('12,840', { exact: false }).getBoundingClientRect();
    const unit = screen.getByText('messages').getBoundingClientRect();
    expect(figure.top).toBeGreaterThanOrEqual(label.bottom);
    expect(unit.left).toBeGreaterThan(figure.left);
  });
});
