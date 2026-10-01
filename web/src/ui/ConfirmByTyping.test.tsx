import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../test/render.tsx';
import { ConfirmByTyping } from './ConfirmByTyping.tsx';

describe('ConfirmByTyping', () => {
  it.each(['default', 'danger'] as const)('arms only on the exact name, with tone %s', async (tone) => {
    const user = userEvent.setup();
    const onConfirm = vi.fn();
    renderWithProviders(<ConfirmByTyping token="ORDERS" tone={tone} confirmLabel="Do it" onConfirm={onConfirm} />);
    const button = screen.getByRole('button', { name: 'Do it' });
    await user.type(screen.getByRole('textbox', { name: 'Type "ORDERS" to confirm' }), 'ORDER');
    expect(button).toBeDisabled();
    await user.type(screen.getByRole('textbox'), 'S');
    await user.click(button);
    expect(onConfirm).toHaveBeenCalledOnce();
  });
});
