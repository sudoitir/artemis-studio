import { describe, expect, it, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';

import { axeViolations, renderThemed, SCHEMES } from '../test/browser.tsx';
import { HoldToConfirm } from './HoldToConfirm.tsx';

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

function channels(color: string): [number, number, number] {
  const [r, g, b] = color.match(/[\d.]+/g)!.map(Number);
  return [r, g, b];
}

function luminance(color: string): number {
  const [r, g, b] = channels(color).map((value) => {
    const c = value / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

const contrast = (a: string, b: string) => {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
};

describe('HoldToConfirm in a real browser', () => {
  describe.each(SCHEMES)('in the %s scheme', (scheme) => {
    it('has no accessibility violations', async () => {
      const { container } = renderThemed(<HoldToConfirm label="Delete 37 queues" onConfirm={() => {}} />, scheme);
      expect(await axeViolations(container)).toEqual([]);
    });

    it('keeps its label legible on both sides of the fill', () => {
      renderThemed(<HoldToConfirm label="Delete 37 queues" onConfirm={() => {}} />, scheme);
      const button = screen.getByRole('button', { name: 'Delete 37 queues' });
      const fill = button.querySelector<HTMLElement>('[aria-hidden="true"]')!;
      const base = getComputedStyle(button);
      const filled = getComputedStyle(fill);
      // The label over the page, and the same label over the fill: each at the text floor.
      const surface = getComputedStyle(document.body).backgroundColor;
      expect(
        contrast(base.color, surface === 'rgba(0, 0, 0, 0)' ? 'rgb(255, 255, 255)' : surface),
      ).toBeGreaterThanOrEqual(scheme === 'dark' ? 4.5 : 4.5);
      expect(contrast(filled.color, filled.backgroundColor)).toBeGreaterThanOrEqual(4.5);
    });
  });

  it('opens its fill while held, drains when let go early, and acts once when full', async () => {
    const onConfirm = vi.fn();
    renderThemed(<HoldToConfirm label="Delete 37 queues" onConfirm={onConfirm} />, 'light');
    const button = screen.getByRole('button', { name: 'Delete 37 queues' });
    const fill = button.querySelector<HTMLElement>('[aria-hidden="true"]')!;
    const closed = getComputedStyle(fill).clipPath;

    fireEvent.mouseDown(button);
    await sleep(600);
    const partway = getComputedStyle(fill).clipPath;
    // `inset(0px N% 0px 0px)`: the share of the width still covered, between none and all of it.
    const covered = Number(/inset\(0px ([\d.]+)%/.exec(partway)?.[1]);
    expect(covered).toBeGreaterThan(1);
    expect(covered).toBeLessThan(99);
    fireEvent.mouseUp(button);
    expect(onConfirm).not.toHaveBeenCalled();
    await sleep(500);
    expect(getComputedStyle(fill).clipPath).toBe(closed);

    fireEvent.mouseDown(button);
    await sleep(1800);
    fireEvent.mouseUp(button);
    expect(onConfirm).toHaveBeenCalledOnce();
    expect(getComputedStyle(fill).clipPath).toMatch(/^inset\(0px( 0%( 0px 0px)?)?\)$/);
  });
});
