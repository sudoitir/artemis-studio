import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { axeViolations, Frame, renderThemed, SCHEMES } from '../test/browser.tsx';
import { Pager } from './Pager.tsx';

/** The pager's own box: the parent of the sentence that states the position. */
const boxOf = (position: RegExp) => screen.getByText(position).parentElement!.getBoundingClientRect();

describe('Pager', () => {
  describe.each(SCHEMES)('in the %s scheme', (scheme) => {
    it('has no accessibility violations on one page and on several', async () => {
      const { container } = renderThemed(
        <Frame width={960}>
          <Pager page={1} pageSize={50} total={14} onChange={() => {}} label="flows" />
          <Pager page={2} pageSize={50} total={120} onChange={() => {}} label="queues" />
        </Frame>,
        scheme,
      );
      expect(await axeViolations(container)).toEqual([]);
    });
  });

  it('holds the same height with and without its buttons, so the rows below do not move', () => {
    renderThemed(
      <Frame width={960}>
        <Pager page={1} pageSize={50} total={14} onChange={() => {}} label="flows" />
        <Pager page={2} pageSize={50} total={120} onChange={() => {}} label="queues" />
      </Frame>,
      'light',
    );
    expect(screen.queryByRole('button', { name: 'Next' })).toBeInTheDocument();
    const single = boxOf(/of 14 flows/);
    const paged = boxOf(/of 120 queues/);
    expect(single.height).toBeGreaterThan(0);
    expect(single.height).toBeCloseTo(paged.height, 1);
  });
});
