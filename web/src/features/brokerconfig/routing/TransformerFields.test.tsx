import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../../test/render.tsx';
import { TransformerFields, type TransformerValue } from './TransformerFields.tsx';

function Harness({ initial }: Readonly<{ initial: TransformerValue }>) {
  const [value, setValue] = useState(initial);
  return (
    <>
      <TransformerFields value={value} onChange={setValue} what="forwarded" />
      <output aria-label="state">{JSON.stringify(value)}</output>
    </>
  );
}

const state = () => JSON.parse(screen.getByLabelText('state').textContent!) as TransformerValue;

describe('TransformerFields', () => {
  it('lists the properties in a table whose values are inputs, each named for its property', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <Harness initial={{ className: 'com.example.Stamp', properties: { tenant: 'eu', zone: 'a' } }} />,
    );

    const table = screen.getByRole('table', { name: 'Transformer properties' });
    expect(within(table).getByRole('rowheader', { name: 'tenant' })).toBeInTheDocument();
    const value = within(table).getByRole('textbox', { name: 'Value of transformer property tenant' });
    expect(value).toHaveValue('eu');

    await user.clear(value);
    await user.type(value, 'us');
    expect(state().properties).toEqual({ tenant: 'us', zone: 'a' });
  });

  it('removes a property, and draws no table when there are none', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness initial={{ className: 'com.example.Stamp', properties: { tenant: 'eu' } }} />);

    await user.click(screen.getByRole('button', { name: 'Remove transformer property tenant' }));

    expect(state().properties).toEqual({});
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('adds a property from the pair of fields, and says properties alone configure nothing', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness initial={{ className: '', properties: {} }} />);

    await user.type(screen.getByRole('textbox', { name: 'Add a transformer property' }), 'region');
    await user.type(screen.getByRole('textbox', { name: 'Value' }), 'dr');
    await user.click(screen.getByRole('button', { name: 'Add property' }));

    expect(state().properties).toEqual({ region: 'dr' });
    expect(screen.getByText(/not applied while no transformer class is declared/)).toBeInTheDocument();
  });
});
