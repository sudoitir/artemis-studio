import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { axeViolations, Frame, frameOf, renderThemed, SCHEMES } from '../test/browser.tsx';
import { Page } from './Page.tsx';
import { PageHeader } from './PageHeader.tsx';
import { Section } from './Section.tsx';

const parts = [
  <PageHeader key="header" title="Queues" description="Every queue on the cluster." />,
  <Section key="section" title="Backlog">
    <p>Nothing is waiting.</p>
  </Section>,
  <div key="last">The last part.</div>,
];

describe('Page', () => {
  describe.each(SCHEMES)('in the %s scheme', (scheme) => {
    it('has no accessibility violations', async () => {
      const { container } = renderThemed(
        <Frame width={960}>
          <Page>{parts}</Page>
        </Frame>,
        scheme,
      );
      expect(await axeViolations(container)).toEqual([]);
    });
  });

  it('stacks its parts in one column, one spacing step apart', () => {
    const { container } = renderThemed(
      <Frame width={960}>
        <Page>{parts}</Page>
      </Frame>,
      'light',
    );
    const page = frameOf(container).firstElementChild!;
    const gap = Number.parseFloat(getComputedStyle(page).rowGap);
    expect(gap).toBeGreaterThan(0);
    const boxes = [...page.children].map((child) => child.getBoundingClientRect());
    expect(boxes).toHaveLength(3);
    for (let i = 1; i < boxes.length; i++) expect(boxes[i].top - boxes[i - 1].bottom).toBeCloseTo(gap, 0);
    for (const box of boxes) expect(box.width).toBeLessThanOrEqual(960);
  });

  it('gives the window height that remains to its last part when it fills', () => {
    renderThemed(
      <div style={{ display: 'flex', flexDirection: 'column', blockSize: 600, inlineSize: 960 }}>
        <Page fill>
          <PageHeader title="Queues" />
          <div>The grid</div>
        </Page>
      </div>,
      'light',
    );
    const last = screen.getByText('The grid').getBoundingClientRect();
    const page = screen.getByText('The grid').parentElement!.getBoundingClientRect();
    expect(page.height).toBeCloseTo(600, 0);
    expect(last.bottom).toBeCloseTo(page.bottom, 0);
  });
});
