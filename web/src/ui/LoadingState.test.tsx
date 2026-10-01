import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { renderWithProviders } from '../test/render.tsx';
import { LoadingState } from './LoadingState.tsx';

describe('LoadingState', () => {
  it('is busy and names what is loading', () => {
    renderWithProviders(<LoadingState label="Loading queues" />);
    const status = screen.getByRole('status');
    expect(status).toHaveAttribute('aria-busy', 'true');
    expect(status).toHaveTextContent('Loading queues');
  });

  it('reserves the size it is given', () => {
    renderWithProviders(<LoadingState label="Loading queues" blockSize="12rem" inlineSize="20rem" />);
    const style = screen.getByRole('status').getAttribute('style');
    expect(style).toContain('12rem');
    expect(style).toContain('20rem');
  });

  it('can sit in a line of text', () => {
    renderWithProviders(<LoadingState label="Loading nodes" variant="inline" />);
    expect(screen.getByRole('status')).toHaveAttribute('data-variant', 'inline');
  });
});
