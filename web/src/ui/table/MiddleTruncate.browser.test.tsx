import { describe, expect, it } from 'vitest';

import { renderThemed, settle } from '../../test/browser.tsx';
import { MiddleTruncate } from './MiddleTruncate.tsx';

/**
 * How an identifier is shortened, drawn by Chromium: the start, the ellipsis and the tail abut, whatever
 * the width. `text-overflow` left a gap of up to one character after the ellipsis, and only a real layout
 * shows it.
 */

const NAME = 'qa.orders.tenant-eu-central-1.billing-platform.region-primary.ap-south.003';
const TAIL = 'ap-south.003'.slice(-12);

/** The box the identifier is drawn in, `width` px wide, once its ellipsis has settled. */
async function draw(width: number, text = NAME) {
  const { container } = renderThemed(
    <div style={{ inlineSize: width, overflow: 'hidden', whiteSpace: 'nowrap' }}>
      <MiddleTruncate text={text} />
    </div>,
    'light',
  );
  const start = container.querySelector<HTMLElement>('[data-clip]')!;
  const tail = [...container.querySelectorAll<HTMLElement>('span')].find((s) => s.textContent === TAIL)!;
  await settle(() => `${start.getBoundingClientRect().width}${tail.dataset.ellipsis}`);
  return { container, start, tail };
}

/** Where the tail's text begins, which is where its leading ellipsis ends. */
function tailTextStart(tail: HTMLElement): number {
  const range = document.createRange();
  range.selectNodeContents(tail.firstChild!);
  return range.getBoundingClientRect().left;
}

describe('a middle-shortened identifier', () => {
  // Each width puts the last whole character of the start at a different distance from its box's edge,
  // which is the slack `text-overflow` left between the ellipsis and the tail.
  const WIDTHS = Array.from({ length: 24 }, (_, i) => 150 + i * 3);

  it.each(WIDTHS)('has its start, its ellipsis and its tail abut in a box %i px wide', async (width) => {
    const { container, start, tail } = await draw(width);
    expect(tail).toHaveAttribute('data-ellipsis');
    expect(getComputedStyle(tail, '::before').content).toMatch(/^"…"/);
    // No space between the start's box and the tail's, and the ellipsis fills the tail's own lead.
    expect(tail.getBoundingClientRect().left - start.getBoundingClientRect().right).toBeCloseTo(0, 1);
    const ellipsis = tailTextStart(tail) - tail.getBoundingClientRect().left;
    expect(ellipsis).toBeGreaterThan(0);
    expect(ellipsis).toBeLessThan(parseFloat(getComputedStyle(tail).fontSize));
    // The whole identifier stays inside its box: the tail's last character is drawn.
    expect(tail.getBoundingClientRect().right).toBeLessThanOrEqual(container.getBoundingClientRect().right + 0.5);
  });

  it('shows no ellipsis, and the whole name, when it fits', async () => {
    const { start, tail } = await draw(900);
    expect(tail).not.toHaveAttribute('data-ellipsis');
    expect(getComputedStyle(tail, '::before').content).toBe('none');
    expect(start.scrollWidth).toBeLessThanOrEqual(start.clientWidth);
  });

  it('draws a short name as plain text', () => {
    const { container } = renderThemed(<MiddleTruncate text="orders.a" />, 'light');
    expect(container.querySelector('[data-clip]')).toBeNull();
    expect(container).toHaveTextContent('orders.a');
  });
});
