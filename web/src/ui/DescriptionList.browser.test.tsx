import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { axeViolations, Frame, frameOf, renderThemed, SCHEMES } from '../test/browser.tsx';
import { DescriptionList, type DescriptionItem } from './DescriptionList.tsx';

const ITEMS: DescriptionItem[] = [
  { term: 'Address', value: 'orders.created' },
  { term: 'Routing type', value: 'ANYCAST', hint: 'Each message goes to one consumer.' },
  { term: 'Identifier', value: 'tenant-eu-central-1.billing-platform.region-primary.'.repeat(5) },
  { term: 'Durable', value: 'yes' },
];

describe('DescriptionList', () => {
  describe.each(SCHEMES)('in the %s scheme', (scheme) => {
    it.each([1, 2] as const)('has no accessibility violations in %i columns', async (columns) => {
      const { container } = renderThemed(
        <Frame width={960}>
          <DescriptionList items={ITEMS} columns={columns} label="Queue" />
        </Frame>,
        scheme,
      );
      expect(await axeViolations(container)).toEqual([]);
    });
  });

  it('keeps every term in one column and every value in the next, wrapping a long value inside it', () => {
    const { container } = renderThemed(
      <Frame width={960}>
        <DescriptionList items={ITEMS} label="Queue" />
      </Frame>,
      'light',
    );
    const terms = ITEMS.map((item) => screen.getByText(item.term).getBoundingClientRect());
    for (const term of terms) expect(term.left).toBeCloseTo(terms[0].left, 0);
    const long = screen.getByText(ITEMS[2].value as string).getBoundingClientRect();
    expect(long.left).toBeGreaterThan(terms[2].right);
    expect(long.right).toBeLessThanOrEqual(frameOf(container).getBoundingClientRect().right + 1);
    // Wrapped onto several lines: taller than a line of the term beside it.
    const lines = document.createRange();
    lines.selectNodeContents(screen.getByText(ITEMS[2].value as string));
    expect(lines.getClientRects().length).toBeGreaterThan(1);
    const list = frameOf(container).firstElementChild as HTMLElement;
    expect(list.scrollWidth).toBeLessThanOrEqual(list.clientWidth);
  });

  it('sets two term and value pairs side by side when asked for two columns', () => {
    renderThemed(
      <Frame width={960}>
        <DescriptionList items={ITEMS.slice(0, 2)} columns={2} label="Queue" />
      </Frame>,
      'light',
    );
    const first = screen.getByText('Address').getBoundingClientRect();
    const second = screen.getByText('Routing type').getBoundingClientRect();
    expect(second.top).toBeCloseTo(first.top, 0);
    expect(second.left).toBeGreaterThan(first.right);
  });

  it('puts a hint under its value', () => {
    renderThemed(
      <Frame width={960}>
        <DescriptionList items={ITEMS} label="Queue" />
      </Frame>,
      'light',
    );
    // The value is the first text of its `dd`; the `dd` itself also holds the hint.
    const range = document.createRange();
    range.selectNode(screen.getByText('ANYCAST').firstChild!);
    const value = range.getBoundingClientRect();
    const hint = screen.getByText('Each message goes to one consumer.').getBoundingClientRect();
    expect(hint.top).toBeGreaterThanOrEqual(value.bottom - 1);
  });
});
