import { afterAll, afterEach, describe, expect, it } from 'vitest';
import { page as browserPage, userEvent } from 'vitest/browser';
import { screen } from '@testing-library/react';

import { axeViolations, renderThemedWithFeatures, SCHEMES, settle } from '../../test/browser.tsx';
import { setShortcutsHelpOpen } from './shortcuts.ts';
import { ShortcutsHelp } from './ShortcutsHelp.tsx';

// A 1280 × 800 window at 100%, and the same window at 200% zoom: half the CSS pixels each way.
const WINDOWS = [
  { name: '1280 px', width: 1280, height: 800 },
  { name: '1280 px at 200% zoom', width: 640, height: 400 },
] as const;

async function open(scheme: (typeof SCHEMES)[number]) {
  // Every feature's views, so the "Go to" lists are as long as they are in the product.
  renderThemedWithFeatures(<ShortcutsHelp />, scheme);
  await userEvent.click(screen.getByRole('button', { name: 'Keyboard shortcuts' }));
  const dialog = await screen.findByRole('dialog', { name: 'Keyboard shortcuts' });
  // Measured once the popover has finished fading in and placing itself: mid-fade, its text is paler than it is.
  await settle(() => `${JSON.stringify(dialog.getBoundingClientRect())} ${getComputedStyle(dialog).opacity}`);
  return dialog;
}

describe('ShortcutsHelp', () => {
  afterAll(() => browserPage.viewport(1920, 1080));
  // Whether it is open outlives a render: closed again, so the next test's click opens it rather than closing it.
  afterEach(() => setShortcutsHelpOpen(false));

  describe.each(WINDOWS)('in a $name window', ({ width, height }) => {
    it.each(SCHEMES)('never scrolls sideways, and keeps inside the window, in the %s scheme', async (scheme) => {
      await browserPage.viewport(width, height);
      const dialog = await open(scheme);

      // Nothing that scrolls or clips is wider than its box: not the popover, its scroll area, a list or a
      // line of keys. A switch's track, which holds no text and clips its own thumb, is decoration.
      const overflowing = [dialog, ...dialog.querySelectorAll<HTMLElement>('*')]
        .filter((el) => el.textContent?.trim())
        .filter((el) => el === dialog || getComputedStyle(el).overflowX !== 'visible' || el.tagName === 'DL')
        .filter((el) => el.scrollWidth > el.clientWidth + 1)
        .map((el) => `${el.tagName.toLowerCase()} "${el.textContent?.slice(0, 40)}"`);
      expect(overflowing).toEqual([]);
      const box = dialog.getBoundingClientRect();
      expect(box.left).toBeGreaterThanOrEqual(0);
      expect(box.right).toBeLessThanOrEqual(document.documentElement.clientWidth);
      expect(await axeViolations(dialog)).toEqual([]);
    });
  });
});
