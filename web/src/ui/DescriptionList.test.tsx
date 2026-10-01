import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { renderWithProviders } from '../test/render.tsx';
import { DescriptionList } from './DescriptionList.tsx';

const items = [
  { term: 'Address', value: 'orders.created' },
  { term: 'Routing type', value: 'ANYCAST', hint: 'One consumer receives each message.' },
];

describe('DescriptionList', () => {
  it('pairs each term with its value', () => {
    renderWithProviders(<DescriptionList items={items} />);
    expect(screen.getAllByRole('term').map((t) => t.textContent)).toEqual(['Address', 'Routing type']);
    const values = screen.getAllByRole('definition');
    expect(values[0]).toHaveTextContent('orders.created');
    expect(values[1]).toHaveTextContent('ANYCAST');
  });

  it('shows a hint under its value', () => {
    renderWithProviders(<DescriptionList items={items} />);
    expect(screen.getByText('One consumer receives each message.')).toBeVisible();
  });

  it('names the list when given a label', () => {
    renderWithProviders(<DescriptionList items={items} label="Address details" />);
    expect(screen.getByRole('group', { name: 'Address details' })).toBeInTheDocument();
  });

  it('is not a group without a label', () => {
    renderWithProviders(<DescriptionList items={items} columns={2} />);
    expect(screen.queryByRole('group')).not.toBeInTheDocument();
  });
});
