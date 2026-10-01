import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { renderWithProviders } from '../test/render.tsx';
import { StatusBadge } from './StatusBadge.tsx';

describe('StatusBadge', () => {
  it('states the state as text, so it reads without colour', () => {
    renderWithProviders(<StatusBadge tone="danger">Split brain</StatusBadge>);
    expect(screen.getByText('Split brain')).toBeVisible();
  });

  it('is neutral unless told otherwise', () => {
    renderWithProviders(<StatusBadge>Backup</StatusBadge>);
    expect(screen.getByText('Backup')).toHaveAttribute('data-tone', 'neutral');
  });

  it.each(['info', 'warning', 'danger'] as const)('carries the %s tone', (tone) => {
    renderWithProviders(<StatusBadge tone={tone}>State</StatusBadge>);
    expect(screen.getByText('State')).toHaveAttribute('data-tone', tone);
  });
});
