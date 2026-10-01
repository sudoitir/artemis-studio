import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { renderWithProviders } from '../test/render.tsx';
import { Stat } from './Stat.tsx';

describe('Stat', () => {
  it('shows a figure with its label and unit', () => {
    renderWithProviders(<Stat label="Throughput" value="1,204" unit="msg/s" />);
    expect(screen.getByRole('term')).toHaveTextContent('Throughput');
    expect(screen.getByRole('definition')).toHaveTextContent('1,204msg/s');
  });

  it('shows a real zero as 0', () => {
    renderWithProviders(<Stat label="Backlog" value={0} />);
    expect(screen.getByRole('definition')).toHaveTextContent('0');
    expect(screen.queryByText('Unavailable')).not.toBeInTheDocument();
  });

  it('says Unavailable for null, never 0, with the reason readable and attached to the value', () => {
    renderWithProviders(<Stat label="Backlog" value={null} unit="msg" unavailableReason="The node did not answer." />);
    const unavailable = screen.getByText('Unavailable');
    expect(unavailable).toBeVisible();
    expect(screen.queryByText('0')).not.toBeInTheDocument();
    expect(screen.queryByText('msg')).not.toBeInTheDocument();
    expect(screen.getByText('The node did not answer.')).toBeVisible();
    expect(screen.getByRole('definition', { description: 'The node did not answer.' })).toBeInTheDocument();
  });

  it('says Unavailable without a reason when none is given', () => {
    renderWithProviders(<Stat label="Backlog" value={null} />);
    expect(screen.getByText('Unavailable')).toBeVisible();
  });

  it('is busy and announces loading instead of a figure or Unavailable', () => {
    const { container } = renderWithProviders(<Stat label="Backlog" value={null} unavailableReason="Why." loading />);
    expect(container.querySelector('[aria-busy="true"]')).not.toBeNull();
    expect(screen.getByText('Loading')).toBeInTheDocument();
    expect(screen.queryByText('Unavailable')).not.toBeInTheDocument();
    expect(screen.queryByText('Why.')).not.toBeInTheDocument();
  });
});
