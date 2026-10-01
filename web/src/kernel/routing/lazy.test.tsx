import { describe, expect, it } from 'vitest';
import { act, screen } from '@testing-library/react';

import { renderWithProviders } from '../../test/render.tsx';
import { lazySlot } from './lazy.tsx';

describe('lazySlot', () => {
  it('holds a named, sized frame until the contribution arrives, then shows it', async () => {
    let arrive: () => void = () => {};
    const Slot = lazySlot(
      () =>
        new Promise<{ Card: () => React.ReactNode }>((resolve) => {
          arrive = () => resolve({ Card: () => <p>the card</p> });
        }),
      'Card',
      '9rem',
    );
    // The slot suspends while it renders, so the render is awaited inside act.
    await act(async () => {
      renderWithProviders(<Slot />);
    });

    const loading = screen.getByRole('status');
    expect(loading).toHaveTextContent('Loading');
    expect(loading.getAttribute('style')).toContain('9rem');
    await act(async () => {
      arrive();
    });
    expect(await screen.findByText('the card')).toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });
});
