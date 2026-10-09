import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import type { PermissionView } from './api.ts';
import { PermissionPicker } from './PermissionPicker.tsx';

const CATALOGUE: PermissionView[] = [
  {
    action: 'queue:create',
    label: 'Create queues',
    featureId: 'queues',
    featureTitle: 'Queues',
    scope: 'CLUSTER',
    resourceKinds: [],
    requires: [],
  },
  {
    action: 'queue:delete',
    label: 'Destroy queues',
    featureId: 'queues',
    featureTitle: 'Queues',
    scope: 'CLUSTER',
    resourceKinds: [],
    requires: [],
  },
  {
    action: 'user:admin',
    label: 'Manage users',
    featureId: 'security',
    featureTitle: 'Security',
    scope: 'GLOBAL',
    resourceKinds: [],
    requires: [],
  },
  {
    action: 'acme:read',
    label: 'Read notes',
    featureId: 'acme',
    featureTitle: 'Acme notes',
    scope: 'CLUSTER',
    resourceKinds: [],
    requires: [],
  },
];

const RESOURCE_CATALOGUE: PermissionView[] = [
  {
    action: 'queue:read',
    label: 'Read queues',
    featureId: 'queues',
    featureTitle: 'Queues',
    scope: 'RESOURCE',
    resourceKinds: ['QUEUE'],
    requires: [],
  },
  {
    action: 'queue:purge',
    label: 'Purge queues',
    featureId: 'queues',
    featureTitle: 'Queues',
    scope: 'RESOURCE',
    resourceKinds: ['QUEUE'],
    requires: ['queue:read'],
  },
  {
    action: 'queue:delete',
    label: 'Delete queues',
    featureId: 'queues',
    featureTitle: 'Queues',
    scope: 'RESOURCE',
    resourceKinds: ['QUEUE'],
    requires: ['queue:purge'],
  },
  {
    action: 'broker:restart',
    label: 'Restart brokers',
    featureId: 'brokers',
    featureTitle: 'Brokers',
    scope: 'CLUSTER',
    resourceKinds: [],
    requires: [],
  },
  {
    action: 'team:admin',
    label: 'Manage teams',
    featureId: 'security',
    featureTitle: 'Security',
    scope: 'GLOBAL',
    resourceKinds: [],
    requires: [],
  },
];

function Harness({
  initial = [] as string[],
  catalogue = CATALOGUE,
  teamRole = false,
}: Readonly<{ initial?: string[]; catalogue?: PermissionView[]; teamRole?: boolean }>) {
  const [value, setValue] = useState(initial);
  return (
    <>
      <PermissionPicker catalogue={catalogue} value={value} onChange={setValue} teamRole={teamRole} />
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

  it('marks where each permission takes effect', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness catalogue={[...CATALOGUE, ...RESOURCE_CATALOGUE.slice(0, 1)]} />);

    await user.click(screen.getByRole('button', { name: /Security/ }));
    expect(screen.getByText('Global')).toBeInTheDocument();
    expect(screen.getByText(/Has no effect when granted on an environment or cluster/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Acme notes/ }));
    expect(screen.getAllByText('Cluster').length).toBeGreaterThan(0);

    await user.click(screen.getByRole('button', { name: /Queues, 0 of 3 selected/ }));
    expect(screen.getAllByText('Resource: queue').length).toBeGreaterThan(0);
  });

  it('adds what a chosen permission requires, and says so', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness catalogue={RESOURCE_CATALOGUE} />);

    await user.click(screen.getByRole('button', { name: /Queues, 0 of 3 selected/ }));
    await user.click(await screen.findByRole('checkbox', { name: /queue:delete/ }));

    expect(selected()).toBe('queue:delete,queue:purge,queue:read');
    const note = screen.getByRole('status', { name: 'Permissions added' });
    expect(note).toHaveTextContent('Added queue:purge, required by queue:delete');
    expect(note).toHaveTextContent('Added queue:read, required by queue:purge');
  });

  it('shows what a wildcard grants as granted through it, counted as held, with the group open', async () => {
    renderWithProviders(<Harness catalogue={RESOURCE_CATALOGUE} initial={['queue:*']} />);

    expect(screen.getByRole('button', { name: /Queues, 3 of 3 selected/ })).toHaveAttribute('aria-expanded', 'true');
    const purge = screen.getByRole('checkbox', { name: /queue:purge/ });
    expect(purge).toBeChecked();
    expect(purge).toBeDisabled();
    expect(purge).toHaveAccessibleDescription(/Granted through queue:\*\./);
    expect(screen.getByRole('checkbox', { name: 'Select all in Queues' })).toBeDisabled();
    expect(screen.getByRole('button', { name: /Brokers, 0 of 1 selected/ })).toHaveAttribute('aria-expanded', 'false');
  });

  it('removes a permission others require at once, with them, says which, and undoes it', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <Harness catalogue={RESOURCE_CATALOGUE} initial={['queue:read', 'queue:purge', 'queue:delete']} />,
    );

    await user.click(await screen.findByRole('checkbox', { name: /queue:read/ }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(selected()).toBe('');
    const notice = screen.getByText('Removed with it').closest('output')!;
    expect(notice).toHaveTextContent('queue:purge (needs queue:read)');
    expect(notice).toHaveTextContent('queue:delete (needs queue:purge)');

    await user.click(within(notice).getByRole('button', { name: 'Undo' }));
    expect(selected()).toBe('queue:delete,queue:purge,queue:read');
    expect(screen.queryByText('Removed with it')).not.toBeInTheDocument();
  });

  it('removes a permission nobody requires without asking', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness catalogue={RESOURCE_CATALOGUE} initial={['queue:read', 'queue:purge']} />);

    await user.click(await screen.findByRole('checkbox', { name: /queue:purge/ }));

    expect(screen.queryByText('Removed with it')).not.toBeInTheDocument();
    expect(selected()).toBe('queue:read');
  });

  it('offers a team role only resource permissions and team:admin', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness catalogue={RESOURCE_CATALOGUE} teamRole />);

    expect(screen.queryByRole('button', { name: /Brokers/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Queues, 0 of 3 selected/ })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Security, 0 of 1 selected/ }));
    expect(await screen.findByRole('checkbox', { name: /team:admin/ })).toBeInTheDocument();
    expect(screen.getByText('Team')).toBeInTheDocument();
  });

  it('lists what a team role holds that it may not, and removes it on request', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness catalogue={RESOURCE_CATALOGUE} initial={['broker:restart', 'queue:read']} teamRole />);

    const notice = screen.getByText('Not allowed in a team role').closest('output')!;
    expect(notice).toHaveTextContent('Not allowed in a team role');
    expect(notice).toHaveTextContent('broker:restart');

    await user.click(screen.getByRole('button', { name: 'Remove them' }));
    expect(selected()).toBe('queue:read');
  });

  it('keeps a held permission the catalogue lacks, under its own group', async () => {
    renderWithProviders(<Harness initial={['gone:read']} />);

    expect(screen.getByRole('button', { name: /Not in the catalogue, 1 of 1 selected/ })).toBeInTheDocument();
    expect(await screen.findByRole('checkbox', { name: /gone:read/ })).toBeChecked();
    expect(selected()).toBe('gone:read');
  });

  it('shows a held wildcard as a wildcard, not as missing', async () => {
    renderWithProviders(<Harness initial={['queue:*']} />);

    expect(screen.queryByRole('button', { name: /Not in the catalogue/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Wildcards, 1 of 1 selected/ })).toBeInTheDocument();
    expect(await screen.findByText(/Grants every queue: permission/)).toBeInTheDocument();
  });
});
