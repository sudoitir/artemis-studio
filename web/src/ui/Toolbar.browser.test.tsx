import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { axeViolations, Frame, renderThemed, SCHEMES } from '../test/browser.tsx';
import { Toolbar } from './Toolbar.tsx';

const toolbar = (arrowNavigation: boolean) => (
  <Toolbar
    label="Queue filters"
    arrowNavigation={arrowNavigation}
    start={
      <>
        <button type="button">Pause</button>
        <button type="button">Resume</button>
      </>
    }
    end={<button type="button">Columns</button>}
  />
);

describe('Toolbar', () => {
  describe.each(SCHEMES)('in the %s scheme', (scheme) => {
    it.each([false, true])('has no accessibility violations with arrow navigation %s', async (arrowNavigation) => {
      const { container } = renderThemed(<Frame width={960}>{toolbar(arrowNavigation)}</Frame>, scheme);
      expect(await axeViolations(container)).toEqual([]);
    });
  });

  it('keeps the view controls at the start and the page controls at the end of one row', () => {
    renderThemed(<Frame width={960}>{toolbar(false)}</Frame>, 'light');
    const group = screen.getByRole('group', { name: 'Queue filters' }).getBoundingClientRect();
    const pause = screen.getByRole('button', { name: 'Pause' }).getBoundingClientRect();
    const resume = screen.getByRole('button', { name: 'Resume' }).getBoundingClientRect();
    const columns = screen.getByRole('button', { name: 'Columns' }).getBoundingClientRect();
    expect(pause.left).toBeCloseTo(group.left, 0);
    expect(resume.left).toBeGreaterThan(pause.right);
    expect(columns.right).toBeCloseTo(group.right, 0);
    expect(columns.top + columns.height / 2).toBeCloseTo(pause.top + pause.height / 2, 0);
  });
});
