import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { axeViolations, Frame, frameOf, renderThemed, SCHEMES } from '../test/browser.tsx';
import { PageHeader } from './PageHeader.tsx';

const header = (title = 'Queues') => (
  <PageHeader
    title={title}
    description="Every queue on the cluster, with its depth and consumers."
    meta={<span>production</span>}
    actions={<button type="button">Create queue</button>}
  />
);

describe('PageHeader', () => {
  describe.each(SCHEMES)('in the %s scheme', (scheme) => {
    it('has no accessibility violations', async () => {
      const { container } = renderThemed(<Frame width={960}>{header()}</Frame>, scheme);
      expect(await axeViolations(container)).toEqual([]);
    });
  });

  it('puts the title at the start and the actions at the end of one row', () => {
    const { container } = renderThemed(<Frame width={960}>{header()}</Frame>, 'light');
    const frame = frameOf(container).getBoundingClientRect();
    const title = screen.getByRole('heading', { level: 1 }).getBoundingClientRect();
    const action = screen.getByRole('button', { name: 'Create queue' }).getBoundingClientRect();
    expect(title.left).toBeCloseTo(frame.left, 0);
    expect(action.right).toBeCloseTo(frame.right, 0);
    expect(Math.abs(title.top - action.top)).toBeLessThan(title.height);
  });

  it('wraps a title longer than its row instead of widening the page', () => {
    const { container } = renderThemed(<Frame width={480}>{header('Q'.repeat(300))}</Frame>, 'light');
    const title = screen.getByRole('heading', { level: 1 });
    const frame = frameOf(container);
    expect(frame.scrollWidth).toBeLessThanOrEqual(frame.clientWidth);
    expect(title.getBoundingClientRect().right).toBeLessThanOrEqual(frame.getBoundingClientRect().right + 1);
  });
});
