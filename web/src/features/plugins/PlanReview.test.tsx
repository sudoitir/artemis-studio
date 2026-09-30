import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { info, plan } from './fixtures.ts';
import { PlanReview } from './PlanReview.tsx';

describe('PlanReview', () => {
  it('states who made a new plugin, which Studio versions it supports, and every capability as a sentence', () => {
    renderWithProviders(
      <PlanReview
        plan={plan({
          pendingChangesets: [{ id: '0001', author: 'acme', reversible: true }],
          info: info({
            requires: ['acme-core', 'acme-audit'],
            contributions: {
              ui: true,
              permissions: [
                { action: 'notes:read', description: null },
                { action: 'notes:write', description: 'Write notes' },
              ],
              settingKeys: ['notes.limit'],
              streamTopics: ['notes'],
              mcpTools: [
                { name: 'notes_search', posture: 'read', description: null },
                { name: 'notes_delete', posture: 'write', description: null },
                { name: 'notes_purge', posture: 'destructive', description: null },
              ],
            },
          }),
        })}
      />,
    );

    expect(screen.getByText('Notes 1.0.0')).toBeInTheDocument();
    expect(screen.getByText('by Acme · Apache-2.0 · supports Studio 2026.01.0 and later')).toBeInTheDocument();
    expect(screen.getByText('Shared notes on queues.')).toBeInTheDocument();
    expect(screen.getByText(/Add screens to Studio, which act with the rights of whoever views them/)).toBeVisible();
    expect(screen.getByText(/Run code inside Studio/)).toBeVisible();
    expect(screen.getByText('Give the assistant 1 read-only tool: notes_search.')).toBeVisible();
    expect(
      screen.getByText(/Give the assistant 2 tools that change things: notes_delete, notes_purge\./),
    ).toBeVisible();
    expect(
      screen.getByText('Define 2 permissions you can grant through roles: notes:read, notes:write (Write notes).'),
    ).toBeVisible();
    expect(screen.getByText('Keep 1 setting of its own.')).toBeVisible();
    expect(screen.getByText('Send live updates to open screens.')).toBeVisible();
    expect(screen.getByText('Create its own database schema with 1 change.')).toBeVisible();
    expect(screen.getByText('Depend on acme-core, acme-audit.')).toBeVisible();
    // A fresh install has nothing to compare against.
    expect(screen.queryByText('What changes')).not.toBeInTheDocument();
  });

  it('lists only what a plugin with no screens, tools or schema actually asks for', () => {
    renderWithProviders(
      <PlanReview
        plan={plan({
          info: info({
            description: null,
            license: null,
            until: '2027.01.0',
            contributions: { ui: false, permissions: [], settingKeys: [], streamTopics: [], mcpTools: [] },
          }),
        })}
      />,
    );

    expect(screen.getByText('by Acme · supports Studio 2026.01.0 to 2027.01.0')).toBeInTheDocument();
    expect(screen.queryByText(/Add screens to Studio/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Give the assistant/)).not.toBeInTheDocument();
    expect(screen.queryByText(/permission/)).not.toBeInTheDocument();
    expect(screen.queryByText(/database schema/)).not.toBeInTheDocument();
    expect(screen.queryByText('Show the SQL')).not.toBeInTheDocument();
    expect(screen.getByText(/Run code inside Studio/)).toBeVisible();
  });

  it('shows an update against the installed version: what it adds, removes, and which roles lose a permission', () => {
    renderWithProviders(
      <PlanReview
        plan={plan({
          fromVersion: '0.9.0',
          toVersion: '1.0.0',
          pendingChangesets: [{ id: '0002', author: 'acme', reversible: true }],
          diff: {
            permissionsAdded: ['notes:share'],
            permissionsRemoved: ['notes:admin'],
            settingKeysAdded: ['notes.limit'],
            settingKeysRemoved: ['notes.old'],
            streamTopicsAdded: ['notes.live'],
            streamTopicsRemoved: ['notes.stale'],
            mcpToolsAdded: ['notes_search'],
            mcpToolsRemoved: ['notes_list'],
          },
          rolesLosingPermission: { 'notes:admin': 1, 'notes:old': 3, 'notes:kept': 0 },
          info: info({ changeNotes: 'Adds sharing.' }),
        })}
      />,
    );

    expect(screen.getByText(/\(installed: 0\.9\.0\)/)).toBeInTheDocument();
    for (const line of [
      'Adds permission notes:share',
      'Removes permission notes:admin',
      'Adds assistant tool notes_search',
      'Removes assistant tool notes_list',
      'Adds setting notes.limit',
      'Removes setting notes.old',
      'Adds live topic notes.live',
      'Removes live topic notes.stale',
    ]) {
      expect(screen.getByText(line)).toBeInTheDocument();
    }
    expect(
      screen.getByText('1 role grants notes:admin, which this version removes; those roles lose it.'),
    ).toBeVisible();
    expect(screen.getByText('3 roles grant notes:old, which this version removes; those roles lose it.')).toBeVisible();
    // A permission no role holds loses nothing, so it is not reported.
    expect(screen.queryByText(/notes:kept/)).not.toBeInTheDocument();
    expect(screen.getByText('Adds sharing.')).toBeInTheDocument();
    expect(screen.getByText('Change its own database schema with 1 change.')).toBeVisible();
  });

  it('does not present a reinstall of the same version as an update', () => {
    renderWithProviders(<PlanReview plan={plan({ fromVersion: '1.0.0', toVersion: '1.0.0' })} />);

    expect(screen.queryByText(/installed:/)).not.toBeInTheDocument();
    expect(screen.queryByText('What changes')).not.toBeInTheDocument();
  });

  it('warns about an irreversible schema change before anything is armed, and reveals the SQL on request', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <PlanReview
        plan={plan({
          activationClass: 'BRIEF_MAINTENANCE',
          reversible: false,
          pendingChangesets: [
            { id: '0001', author: 'acme', reversible: false },
            { id: '0002', author: 'acme', reversible: true },
          ],
          updateSql: 'CREATE TABLE note (id uuid);\n',
        })}
      />,
    );

    expect(screen.getByText(/Irreversible: 1 database change cannot be undone/)).toBeVisible();
    expect(screen.getByText(/Its database schema is created, then it starts/)).toBeVisible();
    const toggle = screen.getByRole('button', { name: 'Show the SQL' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');

    await user.click(toggle);
    expect(screen.getByRole('button', { name: 'Hide the SQL' })).toHaveAttribute('aria-expanded', 'true');
    await user.click(screen.getByRole('button', { name: 'Hide the SQL' }));
    expect(screen.getByRole('button', { name: 'Show the SQL' })).toHaveAttribute('aria-expanded', 'false');
  });

  it('says a reversible change can be rolled back by not warning about it', () => {
    renderWithProviders(
      <PlanReview plan={plan({ pendingChangesets: [{ id: '0001', author: 'acme', reversible: true }] })} />,
    );

    expect(screen.queryByText(/Irreversible/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Show the SQL' })).toBeInTheDocument();
  });

  it('blocks on a missing requirement, naming it, and lists the upload warnings', () => {
    const { rerender } = renderWithProviders(<PlanReview plan={plan({ missingRequires: ['acme-core'] })} />);

    expect(screen.getByText('It cannot be activated yet')).toBeInTheDocument();
    expect(screen.getByText(/It requires acme-core, which is not active\. Install or enable it first\./)).toBeVisible();
    expect(screen.queryByText('Worth knowing')).not.toBeInTheDocument();

    rerender(
      <PlanReview
        plan={plan({ missingRequires: ['acme-core', 'acme-audit'] })}
        warnings={[
          { code: 'old-api', message: 'Uses a deprecated API.', fix: 'Move to the new one.', severity: 'WARNING' },
        ]}
      />,
    );
    expect(screen.getByText(/which are not active\. Install or enable them first\./)).toBeVisible();
    expect(screen.getByText('Worth knowing')).toBeInTheDocument();
    expect(screen.getByText('Uses a deprecated API.')).toBeInTheDocument();
  });
});
