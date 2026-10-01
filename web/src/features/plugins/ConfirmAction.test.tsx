import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { ApiError } from '../../kernel/api/request.ts';
import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { ConfirmAction } from './ConfirmAction.tsx';
import { me } from './fixtures.ts';

const STALE = new Date(Date.now() - 60 * 60_000).toISOString();

function open(error: ApiError | null = null) {
  const onConfirm = vi.fn();
  const onClose = vi.fn();
  const user = userEvent.setup();
  renderWithProviders(
    <ConfirmAction
      opened
      onClose={onClose}
      title="Disable Notes"
      confirmLabel="Disable Notes"
      pending={false}
      error={error}
      onConfirm={onConfirm}
    >
      <p>Its screens stop for everyone.</p>
    </ConfirmAction>,
  );
  return { user, onConfirm, onClose };
}

describe('ConfirmAction', () => {
  it('states what it affects, takes focus and closes on Escape', async () => {
    server.use(me());
    const { user, onClose } = open();

    const dialog = await screen.findByRole('dialog', { name: 'Disable Notes' });
    expect(dialog).toHaveTextContent('Its screens stop for everyone.');
    await waitFor(() => expect(dialog).toContainElement(document.activeElement as HTMLElement));
    await user.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('sends the action once the session is fresh', async () => {
    server.use(me());
    const { user, onConfirm } = open();

    await user.click(await screen.findByRole('button', { name: 'Disable Notes' }));

    expect(onConfirm).toHaveBeenCalledTimes(1);
  });

  it('disables the button, and says why, until the sign-in is confirmed', async () => {
    server.use(me(STALE));
    const { user, onConfirm } = open();

    const confirm = await screen.findByRole('button', { name: 'Disable Notes' });
    expect(confirm).toBeDisabled();
    expect(confirm).toHaveAccessibleDescription('Confirm it is you above first.');
    await user.click(confirm);

    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('says why the server refused, with the next step', async () => {
    server.use(me());
    open(
      new ApiError(409, {
        title: 'Refused',
        violations: [{ code: 'x', message: 'acme-extras is active.', fix: 'Disable it first.' }],
      }),
    );

    const refusal = await screen.findByRole('alert');
    expect(refusal).toHaveTextContent('acme-extras is active.');
    expect(refusal).toHaveTextContent('Disable it first.');
  });
});
