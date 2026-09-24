import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';

import { renderWithProviders } from '../../test/render.tsx';
import { guarded } from './guarded.tsx';

function Broken(): never {
  throw new Error('boom');
}

describe('a plugin component that throws', () => {
  it('is replaced by a sentence naming the plugin, and the page around it renders', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const Safe = guarded('acme-notes', Broken);
    renderWithProviders(
      <div>
        <p>Studio content</p>
        <Safe />
      </div>,
    );
    expect(screen.getByText('Studio content')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('The acme-notes plugin could not show this part of the screen');
  });

  it('renders nothing where a sentence would not fit', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const Safe = guarded('acme-notes', Broken, true);
    renderWithProviders(<Safe />);
    expect(screen.queryByRole('status')).toBeNull();
  });
});
