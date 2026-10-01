import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { renderWithProviders } from '../test/render.tsx';
import { Page } from './Page.tsx';

const pageCss = readFileSync(resolve(process.cwd(), 'src/ui/Page.module.css'), 'utf8');

describe('Page', () => {
  it('renders its parts in order', () => {
    renderWithProviders(
      <Page>
        <p>first</p>
        <p>last</p>
      </Page>,
    );
    const parts = screen.getAllByText(/first|last/);
    expect(parts.map((p) => p.textContent)).toEqual(['first', 'last']);
  });

  it('marks the frame as filling only when asked', () => {
    const { rerender } = renderWithProviders(
      <Page>
        <p>content</p>
      </Page>,
    );
    expect(screen.getByText('content').parentElement).not.toHaveAttribute('data-fill');
    rerender(
      <Page fill>
        <p>content</p>
      </Page>,
    );
    expect(screen.getByText('content').parentElement).toHaveAttribute('data-fill');
  });

  it('sizes the filling child from the window less the shell header, with no breakpoints', () => {
    expect(pageCss).toMatch(/100dvh - var\(--app-shell-header-offset/);
    expect(pageCss).not.toMatch(/@media/);
  });
});
