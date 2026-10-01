import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { renderWithProviders } from '../test/render.tsx';
import { PageHeader } from './PageHeader.tsx';

const css = readFileSync(resolve(process.cwd(), 'src/ui/PageHeader.module.css'), 'utf8');

describe('PageHeader', () => {
  it('renders the title as the page`s one h1', () => {
    renderWithProviders(<PageHeader title="Queues" />);
    expect(screen.getAllByRole('heading')).toHaveLength(1);
    expect(screen.getByRole('heading', { level: 1, name: 'Queues' })).toBeInTheDocument();
  });

  it('shows the description, meta and actions beside the title', () => {
    renderWithProviders(
      <PageHeader
        title="Queues"
        description="Every queue on the cluster."
        meta={<span>orders-prod</span>}
        actions={<button type="button">Create queue</button>}
      />,
    );
    expect(screen.getByText('Every queue on the cluster.')).toBeInTheDocument();
    expect(screen.getByText('orders-prod')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create queue' })).toBeInTheDocument();
    // Meta never becomes a second top-level heading.
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
  });

  it('adds no landmark of its own', () => {
    renderWithProviders(<PageHeader title="Queues" actions={<button type="button">Go</button>} />);
    expect(screen.queryByRole('banner')).not.toBeInTheDocument();
    expect(screen.queryByRole('region')).not.toBeInTheDocument();
  });

  it('wraps by content size on both axes, without a breakpoint', () => {
    expect(css).toMatch(/\.root\s*{[^}]*flex-wrap:\s*wrap/);
    expect(css).toMatch(/\.heading\s*{[^}]*flex-wrap:\s*wrap/);
    expect(css).toMatch(/\.actions\s*{[^}]*flex-wrap:\s*wrap/);
    expect(css).not.toMatch(/@media/);
  });
});
