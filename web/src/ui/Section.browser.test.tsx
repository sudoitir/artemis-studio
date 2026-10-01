import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { axeViolations, Frame, renderThemed, SCHEMES } from '../test/browser.tsx';
import { Section } from './Section.tsx';

const section = (variant: 'plain' | 'card') => (
  <Section
    title="Retention"
    description="How long each store keeps what it holds."
    actions={<button type="button">Edit</button>}
    variant={variant}
  >
    <Section title="Audit log" headingLevel={3}>
      <p>Seven days.</p>
    </Section>
  </Section>
);

describe('Section', () => {
  describe.each(SCHEMES)('in the %s scheme', (scheme) => {
    it.each(['plain', 'card'] as const)('has no accessibility violations as a %s block', async (variant) => {
      const { container } = renderThemed(<Frame width={960}>{section(variant)}</Frame>, scheme);
      expect(await axeViolations(container)).toEqual([]);
    });
  });

  it('is a region named by its heading, with the nested one a level below', () => {
    renderThemed(<Frame width={960}>{section('plain')}</Frame>, 'light');
    expect(screen.getByRole('region', { name: 'Retention' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'Retention' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 3, name: 'Audit log' })).toBeInTheDocument();
  });

  it('keeps its actions on the end side of the heading', () => {
    renderThemed(<Frame width={960}>{section('plain')}</Frame>, 'light');
    const region = screen.getByRole('region', { name: 'Retention' }).getBoundingClientRect();
    const heading = screen.getByRole('heading', { level: 2 }).getBoundingClientRect();
    const action = screen.getByRole('button', { name: 'Edit' }).getBoundingClientRect();
    expect(heading.left).toBeCloseTo(region.left, 0);
    expect(action.right).toBeCloseTo(region.right, 0);
  });

  it('frames the card variant as a bordered panel inset by one spacing step', () => {
    renderThemed(<Frame width={960}>{section('card')}</Frame>, 'light');
    const region = screen.getByRole('region', { name: 'Retention' });
    const style = getComputedStyle(region);
    expect(Number.parseFloat(style.borderInlineStartWidth)).toBe(1);
    expect(Number.parseFloat(style.paddingInlineStart)).toBeGreaterThan(0);
    const heading = screen.getByRole('heading', { level: 2 }).getBoundingClientRect();
    expect(heading.left - region.getBoundingClientRect().left).toBeCloseTo(
      Number.parseFloat(style.paddingInlineStart) + 1,
      0,
    );
  });
});
