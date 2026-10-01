import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { createRef } from 'react';
import { describe, expect, it } from 'vitest';
import { screen, within } from '@testing-library/react';

import { renderWithProviders } from '../test/render.tsx';
import { Section } from './Section.tsx';

const css = readFileSync(resolve(process.cwd(), 'src/ui/Section.module.css'), 'utf8');

describe('Section', () => {
  it('titles itself with an h2 by default', () => {
    renderWithProviders(<Section title="Consumers">body</Section>);
    expect(screen.getByRole('heading', { level: 2, name: 'Consumers' })).toBeInTheDocument();
  });

  it('uses an h3 when nested', () => {
    renderWithProviders(
      <Section title="Outer">
        <Section title="Inner" headingLevel={3}>
          body
        </Section>
      </Section>,
    );
    expect(screen.getByRole('heading', { level: 2, name: 'Outer' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 3, name: 'Inner' })).toBeInTheDocument();
  });

  it('is a region named by its heading', () => {
    renderWithProviders(
      <>
        <Section title="Consumers">one</Section>
        <Section title="Addresses">two</Section>
      </>,
    );
    expect(within(screen.getByRole('region', { name: 'Consumers' })).getByText('one')).toBeInTheDocument();
    expect(within(screen.getByRole('region', { name: 'Addresses' })).getByText('two')).toBeInTheDocument();
  });

  it('hands its element to a ref and takes focus by script when it has a tabIndex', () => {
    const ref = createRef<HTMLElement>();
    renderWithProviders(
      <Section title="Consumers" ref={ref} tabIndex={-1}>
        body
      </Section>,
    );
    const region = screen.getByRole('region', { name: 'Consumers' });
    expect(ref.current).toBe(region);
    ref.current?.focus();
    expect(region).toHaveFocus();
  });

  it('is not a tab stop unless asked', async () => {
    renderWithProviders(<Section title="Consumers">body</Section>);
    expect(screen.getByRole('region', { name: 'Consumers' })).not.toHaveAttribute('tabindex');
  });

  it('shows its description and actions', () => {
    renderWithProviders(
      <Section title="Consumers" description="Who is reading." actions={<button type="button">Refresh</button>}>
        body
      </Section>,
    );
    expect(screen.getByText('Who is reading.')).toBeInTheDocument();
    expect(
      within(screen.getByRole('region', { name: 'Consumers' })).getByRole('button', { name: 'Refresh' }),
    ).toBeInTheDocument();
  });

  it('is plain unless it is a card', () => {
    const { rerender } = renderWithProviders(<Section title="Consumers">body</Section>);
    expect(screen.getByRole('region')).toHaveAttribute('data-variant', 'plain');
    rerender(
      <Section title="Consumers" variant="card">
        body
      </Section>,
    );
    expect(screen.getByRole('region')).toHaveAttribute('data-variant', 'card');
  });

  it('wraps its heading row without a breakpoint', () => {
    expect(css).toMatch(/\.header\s*{[^}]*flex-wrap:\s*wrap/);
    expect(css).not.toMatch(/@media/);
  });
});
