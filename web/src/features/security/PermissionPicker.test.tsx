import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import type { PermissionView } from './api.ts';
import { PermissionPicker } from './PermissionPicker.tsx';

const CATALOGUE: PermissionView[] = [
  { action: 'queue:create', label: 'Create queues', featureId: 'queues', featureTitle: 'Queues', globalOnly: false },
  { action: 'queue:delete', label: 'Destroy queues', featureId: 'queues', featureTitle: 'Queues', globalOnly: false },
  { action: 'user:admin', label: 'Manage users', featureId: 'security', featureTitle: 'Security', globalOnly: true },
  { action: 'acme:read', label: 'Read notes', featureId: 'acme', featureTitle: 'Acme notes', globalOnly: false },
];

function Harness({ initial = [] as string[] }) {
  const [value, setValue] = useState(initial);
  return (
    <>
      <PermissionPicker catalogue={CATALOGUE} value={value} onChange={setValue} />
      <div data-value>{[...value].sort().join(',')}</div>
    </>
  );
}

const selected = () => document.querySelector('div[data-value]')!.textContent;

describe('PermissionPicker', () => {
  it('groups by module or plugin and hides groups the search does not match', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    expect(screen.getByRole('button', { name: /Acme notes, 0 of 1 selected/ })).toBeInTheDocument();
    await user.type(screen.getByRole('textbox', { name: 'Search permissions' }), 'destroy');

    expect(screen.getByRole('checkbox', { name: /queue:delete/ })).toBeVisible();
    expect(screen.queryByRole('button', { name: /Security/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Acme notes/ })).not.toBeInTheDocument();
  });

  it('says when nothing matches and clears the search', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    await user.type(screen.getByRole('textbox', { name: 'Search permissions' }), 'zzz');
    expect(screen.getByText(/No permission matches/)).toBeInTheDocument();
    await user.click(screen.getAllByRole('button', { name: 'Clear search' })[0]);

    expect(screen.getByRole('button', { name: /Queues, 0 of 2 selected/ })).toBeInTheDocument();
  });

  it('selects and clears a whole group from the keyboard and announces the count', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    screen.getByRole('checkbox', { name: 'Select all in Queues' }).focus();
    await user.keyboard(' ');
    expect(selected()).toBe('queue:create,queue:delete');
    expect(screen.getByRole('status')).toHaveTextContent('2 of 2 permissions selected in Queues');

    await user.keyboard(' ');
    expect(selected()).toBe('');
    expect(screen.getByRole('status')).toHaveTextContent('0 of 2 permissions selected in Queues');
  });

  it('marks a global-only permission', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    await user.click(screen.getByRole('button', { name: /Security/ }));
    expect(screen.getByText('Global only')).toBeInTheDocument();
    expect(screen.getByText(/Has no effect when granted on an environment or cluster/)).toBeInTheDocument();
  });

  it('keeps a held permission the catalogue lacks, under its own group', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness initial={['gone:read']} />);

    await user.click(screen.getByRole('button', { name: /Not in the catalogue, 1 of 1 selected/ }));
    expect(await screen.findByRole('checkbox', { name: /gone:read/ })).toBeChecked();
    expect(selected()).toBe('gone:read');
  });

  it('shows a held wildcard as a wildcard, not as missing', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness initial={['queue:*']} />);

    expect(screen.queryByRole('button', { name: /Not in the catalogue/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Wildcards, 1 of 1 selected/ }));
    expect(await screen.findByText(/Grants every queue: permission/)).toBeInTheDocument();
  });
});
