import { describe, expect, it, vi } from 'vitest';
import { Modal } from '@mantine/core';
import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { useActionHost } from './hostContext.ts';
import type { HostedDialogProps } from './types.ts';

function Probe({ opened, onClose, onEntered }: HostedDialogProps & { onEntered: () => void }) {
  return (
    <Modal opened={opened} onClose={onClose} title="Probe" onEnterTransitionEnd={onEntered}>
      <button type="button" onClick={onClose}>
        Done
      </button>
    </Modal>
  );
}

function Opener({ onEntered, restoreFocus }: { onEntered: () => void; restoreFocus: () => void }) {
  const host = useActionHost();
  return (
    <button type="button" onClick={() => host.open(Probe, { onEntered }, { restoreFocus })}>
      Open
    </button>
  );
}

describe('ActionHost (ADR-0107)', () => {
  it('opens a dialog after mounting it, so its enter transition — where previews start — fires', async () => {
    const onEntered = vi.fn();
    const restoreFocus = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<Opener onEntered={onEntered} restoreFocus={restoreFocus} />);

    await user.click(screen.getByRole('button', { name: 'Open' }));
    expect(await screen.findByRole('dialog', { name: 'Probe' })).toBeInTheDocument();
    await waitFor(() => expect(onEntered).toHaveBeenCalledTimes(1));

    await user.click(screen.getByRole('button', { name: 'Done' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Probe' })).not.toBeInTheDocument());
    await waitFor(() => expect(restoreFocus).toHaveBeenCalledTimes(1));
  });

  it('does not move focus back when the dialog navigated away', async () => {
    const restoreFocus = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<Opener onEntered={() => {}} restoreFocus={restoreFocus} />);

    await user.click(screen.getByRole('button', { name: 'Open' }));
    await screen.findByRole('dialog', { name: 'Probe' });
    act(() => window.history.pushState({}, '', '/clusters/c1/bulk/run-1'));
    await user.click(screen.getByRole('button', { name: 'Done' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Probe' })).not.toBeInTheDocument());
    await new Promise((r) => setTimeout(r, 400));
    expect(restoreFocus).not.toHaveBeenCalled();
    window.history.pushState({}, '', '/');
  });
});
