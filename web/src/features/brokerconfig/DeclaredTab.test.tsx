import { describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import type { GateVerdict } from '../../ui/capabilityGate.ts';
import type { ConfigDeclarationView } from './api.ts';
import { DeclaredTab } from './DeclaredTab.tsx';
import { CATALOGUE, declaration } from './fixtures.ts';

const ALLOWED: GateVerdict = { kind: 'allowed', uncertain: false };

function show(d: ConfigDeclarationView = declaration(), over: { canWrite?: boolean; applyGate?: GateVerdict } = {}) {
  const onEdit = vi.fn();
  const onApply = vi.fn();
  renderWithProviders(
    <DeclaredTab
      declaration={d}
      catalogue={CATALOGUE}
      canWrite={over.canWrite ?? true}
      applyGate={over.applyGate ?? ALLOWED}
      onApply={onApply}
      onEdit={onEdit}
    />,
  );
  return { onEdit, onApply, user: userEvent.setup() };
}

describe('DeclaredTab', () => {
  it('draws each section as a heading over its own table, never a hand-rolled one', () => {
    show();

    for (const name of ['Addresses and queues', 'Address settings', 'Security settings', 'Diverts', 'Bridges']) {
      expect(screen.getByRole('heading', { level: 2, name })).toBeInTheDocument();
    }
    // A table is named for what it lists and its first column is the row's header.
    const settings = screen.getByRole('table', { name: 'Address settings' });
    expect(within(settings).getByRole('rowheader', { name: 'orders.#' })).toBeInTheDocument();
    for (const header of ['Match', 'Declared keys', 'On the live nodes', 'Actions']) {
      expect(within(settings).getByRole('columnheader', { name: header })).toBeInTheDocument();
    }
  });

  it('reads what is declared beside what the nodes run, as a description list and a state in words', () => {
    show();

    const row = screen.getByRole('row', { name: /orders\.request/ });
    // The declared keys are terms and values, not a second table.
    expect(within(row).getByText('routing').tagName).toBe('DT');
    expect(within(row).getByText('ANYCAST').tagName).toBe('DD');
    expect(within(row).getByText('in sync on 2/2')).toBeInTheDocument();
  });

  it('opens the editor of an item and scopes an apply to exactly that item', async () => {
    const { user, onEdit, onApply } = show();

    await user.click(screen.getByRole('button', { name: 'Edit address setting orders.#' }));
    expect(onEdit).toHaveBeenCalledWith('addressSettings', 'orders.#');

    await user.click(screen.getByRole('button', { name: 'Apply address setting orders.#' }));
    expect(onApply).toHaveBeenCalledWith(
      expect.objectContaining({
        label: 'address setting orders.#',
        stepIds: [
          'ADDRESS_SETTING:orders.#:ADD',
          'ADDRESS_SETTING:orders.#:REPLACE',
          'ADDRESS_SETTING:orders.#:REMOVE',
        ],
      }),
    );
  });

  it('teaches what an empty section is for and keeps its add control, which opens a new entry', async () => {
    const { user, onEdit } = show();

    const diverts = screen.getByRole('region', { name: 'Diverts' });
    expect(within(diverts).getByText('None declared')).toBeInTheDocument();
    expect(within(diverts).getByText(/the live nodes are not compared on it/)).toBeInTheDocument();

    await user.click(within(diverts).getByRole('button', { name: 'Add divert' }));
    expect(onEdit).toHaveBeenCalledWith('diverts');
  });

  it('shows View, not Edit, without the permission, and keeps add visible with its reason', async () => {
    const { user } = show(declaration(), { canWrite: false });

    expect(screen.getByRole('button', { name: 'View address setting orders.#' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit address setting orders.#' })).toBeNull();
    const add = screen.getByRole('button', { name: 'Add divert' });
    expect(add).toBeDisabled();
    // The reason is on a control that takes focus, not on a hover title.
    await user.click(screen.getByRole('button', { name: 'Why adding divert is unavailable' }));
    expect(await screen.findByText(/Needs the "Edit declared configuration" permission/)).toBeInTheDocument();
  });

  it('keeps Apply this visible and explained when applying is blocked', async () => {
    const { user, onApply } = show(declaration(), {
      applyGate: { kind: 'blocked', reason: 'Nothing can be applied while the file owns the configuration.' },
    });

    expect(screen.getByRole('button', { name: 'Apply address setting orders.#' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Why applying address setting orders.# is unavailable' }));
    expect(await screen.findByText(/while the file owns the configuration/)).toBeInTheDocument();
    expect(onApply).not.toHaveBeenCalled();
  });

  it('says what differs on a node, declared to observed, under the state sentence', () => {
    const d = declaration({
      nodes: [
        {
          ...declaration().nodes[0],
          state: 'DRIFTED',
          findings: [
            {
              kind: 'DIVERGENT',
              section: 'ADDRESS_SETTING',
              key: 'orders.#',
              detail: 'differs',
              declared: { addressFullMessagePolicy: 'PAGE' },
              observed: { addressFullMessagePolicy: 'DROP' },
            },
          ],
        },
      ],
    });
    show(d);

    const row = screen.getByRole('row', { name: /orders\.#.*differs on broker-1/ });
    expect(within(row).getByText('DROP').closest('dd')).toHaveTextContent('PAGE → DROP');
  });
});
