import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../test/render.tsx';
import { ConfirmDialog, type ConfirmDialogProps } from './ConfirmDialog.tsx';

function Host(props: Partial<ConfirmDialogProps>) {
  const [opened, setOpened] = useState(false);
  return (
    <>
      <button type="button" onClick={() => setOpened(true)}>
        Open
      </button>
      <ConfirmDialog
        title="Delete queue"
        consequence="Removes queue orders on 3 nodes and its 1,204 messages."
        confirmLabel="Delete queue"
        onConfirm={() => {}}
        {...props}
        opened={opened}
        onClose={() => setOpened(false)}
      />
    </>
  );
}

describe('ConfirmDialog', () => {
  it('states the consequence and runs the named action', async () => {
    const onConfirm = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<Host onConfirm={onConfirm} />);
    await user.click(screen.getByRole('button', { name: 'Open' }));
    const dialog = await screen.findByRole('dialog', { name: 'Delete queue' });
    expect(dialog).toHaveTextContent('Removes queue orders on 3 nodes');
    await user.click(screen.getByRole('button', { name: 'Delete queue' }));
    expect(onConfirm).toHaveBeenCalledOnce();
  });

  it('is operable by keyboard alone: focus enters, escape closes, focus returns to the trigger', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Host tone="danger" />);
    const trigger = screen.getByRole('button', { name: 'Open' });
    trigger.focus();
    await user.keyboard('{Enter}');
    const dialog = await screen.findByRole('dialog', { name: 'Delete queue' });
    // The safe choice holds focus, so an Enter pressed by habit cancels.
    await waitFor(() => expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus());
    expect(dialog).toContainElement(document.activeElement as HTMLElement);
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(trigger).toHaveFocus());
  });

  it('arms a destructive action only on the typed name, by keyboard', async () => {
    const onConfirm = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<Host tone="danger" typedName="orders" onConfirm={onConfirm} />);
    const trigger = screen.getByRole('button', { name: 'Open' });
    trigger.focus();
    await user.keyboard('{Enter}');
    const field = await screen.findByRole('textbox', { name: 'Type "orders" to confirm' });
    await waitFor(() => expect(field).toHaveFocus());
    const confirm = screen.getByRole('button', { name: 'Delete queue' });
    expect(confirm).toBeDisabled();
    await user.keyboard('order');
    expect(confirm).toBeDisabled();
    await user.keyboard('s{Tab}{Enter}');
    expect(onConfirm).toHaveBeenCalledOnce();
  });

  it('cancels from the keyboard and returns focus', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Host />);
    const trigger = screen.getByRole('button', { name: 'Open' });
    await user.click(trigger);
    await screen.findByRole('dialog');
    await user.keyboard('{Enter}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(trigger).toHaveFocus());
  });

  it('is busy while pending: the action cannot be pressed again and the dialog stays open', async () => {
    const onConfirm = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<Host pending onConfirm={onConfirm} />);
    await user.click(screen.getByRole('button', { name: 'Open' }));
    const dialog = await screen.findByRole('dialog');
    const confirm = screen.getByRole('button', { name: 'Delete queue' });
    expect(confirm).toBeDisabled();
    await user.click(confirm);
    expect(onConfirm).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled();
    await user.keyboard('{Escape}');
    expect(dialog).toBeInTheDocument();
  });
});
