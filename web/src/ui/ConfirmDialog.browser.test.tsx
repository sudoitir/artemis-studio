import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { axeViolations, renderThemed, SCHEMES, settle } from '../test/browser.tsx';
import { ConfirmDialog, type ConfirmDialogProps } from './ConfirmDialog.tsx';
import { Notice } from './Notice.tsx';

const base: ConfirmDialogProps = {
  opened: true,
  onClose: () => {},
  title: 'Purge queue',
  consequence: 'Removes every message from orders.created on both nodes. It cannot be undone.',
  confirmLabel: 'Purge queue',
  onConfirm: () => {},
};

const VARIANTS: Record<string, ConfirmDialogProps> = {
  plain: base,
  'danger, held': { ...base, tone: 'danger' },
  blocked: { ...base, blocked: 'A purge is already running on this queue. Wait for it to finish, then try again.' },
  'danger, held, blocked': {
    ...base,
    tone: 'danger',
    blocked: 'The queue has consumers. Close them first.',
  },
  'with a result': {
    ...base,
    result: (
      <Notice title="Queue purged" tone="info">
        Removed 1,204 messages from 3 nodes.
      </Notice>
    ),
  },
};

/** The open dialog, once its entrance transition has finished moving it. */
async function openDialog() {
  const dialog = await screen.findByRole('dialog');
  await settle(() => {
    const box = dialog.getBoundingClientRect();
    return `${box.left},${box.top},${box.width},${box.height}`;
  });
  return dialog;
}

describe('ConfirmDialog', () => {
  describe.each(SCHEMES)('in the %s scheme', (scheme) => {
    it.each(Object.keys(VARIANTS))('has no accessibility violations when %s', async (variant) => {
      renderThemed(<ConfirmDialog {...VARIANTS[variant]} />, scheme);
      expect(await axeViolations(await openDialog())).toEqual([]);
    });
  });

  it('opens centred in the window, inside it, wider than its content needs', async () => {
    renderThemed(<ConfirmDialog {...base} />, 'light');
    const box = (await openDialog()).getBoundingClientRect();
    expect(box.left + box.width / 2).toBeCloseTo(window.innerWidth / 2, 0);
    expect(box.top + box.height / 2).toBeCloseTo(window.innerHeight / 2, 0);
    expect(box.left).toBeGreaterThanOrEqual(0);
    expect(box.right).toBeLessThanOrEqual(window.innerWidth);
  });

  it('states the consequence above the buttons and ends the buttons at the end edge', async () => {
    renderThemed(<ConfirmDialog {...base} />, 'light');
    const dialog = await openDialog();
    const box = dialog.getBoundingClientRect();
    const consequence = screen.getByText(/Removes every message/).getBoundingClientRect();
    const cancel = screen.getByRole('button', { name: 'Cancel' }).getBoundingClientRect();
    const confirm = screen.getByRole('button', { name: 'Purge queue' }).getBoundingClientRect();
    expect(cancel.top).toBeGreaterThanOrEqual(consequence.bottom);
    expect(confirm.left).toBeGreaterThan(cancel.right);
    expect(box.right - confirm.right).toBeLessThan(box.width / 4);
    expect(confirm.right).toBeLessThanOrEqual(box.right);
  });

  it('states the reason beside the disabled button, above it', async () => {
    renderThemed(<ConfirmDialog {...VARIANTS.blocked} />, 'light');
    await openDialog();
    const reason = screen.getByText(/A purge is already running/).getBoundingClientRect();
    const confirm = screen.getByRole('button', { name: 'Purge queue' });
    expect(confirm).toBeDisabled();
    expect(reason.bottom).toBeLessThanOrEqual(confirm.getBoundingClientRect().top);
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeEnabled();
  });

  it('asks for the name before the destructive button can be pressed', async () => {
    renderThemed(<ConfirmDialog {...VARIANTS['danger, typed']} />, 'light');
    await openDialog();
    const field = screen.getByRole('textbox');
    const confirm = screen.getByRole('button', { name: 'Purge queue' });
    expect(confirm).toBeDisabled();
    expect(confirm.getBoundingClientRect().top).toBeGreaterThanOrEqual(field.getBoundingClientRect().bottom);
  });
});
