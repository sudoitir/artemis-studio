import { describe, expect, it } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { ShortcutsHelp } from './ShortcutsHelp.tsx';

describe('ShortcutsHelp', () => {
  it('opens from its header button as a popover, and Escape returns focus to the button', async () => {
    renderWithProviders(<ShortcutsHelp />);
    const button = screen.getByRole('button', { name: 'Keyboard shortcuts' });

    await userEvent.click(button);
    const popover = await screen.findByRole('dialog', { name: 'Keyboard shortcuts' });
    expect(popover).toHaveTextContent('Search views, clusters and queues');
    const toggle = within(popover).getByLabelText(/Single-key shortcuts/);
    expect(toggle).toBeChecked();
    // Focus moves into the popover, onto its one control.
    await waitFor(() => expect(toggle).toHaveFocus());

    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Keyboard shortcuts' })).not.toBeInTheDocument());
    expect(button).toHaveFocus();
  });
});
