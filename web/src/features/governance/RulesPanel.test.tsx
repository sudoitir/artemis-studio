import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { RulesPanel } from './RulesPanel.tsx';

const BUILT_IN = {
  id: 'b1',
  addressPattern: null,
  target: 'PROPERTY',
  selector: 'authorization',
  dataClass: 'CREDENTIAL',
  dataClassLabel: 'credential',
  action: null,
  defaultAction: 'DROP',
  builtin: true,
  enabled: true,
  exception: false,
  updatedAt: '2026-09-14T00:00:00Z',
};

const CUSTOM = {
  ...BUILT_IN,
  id: 'c1',
  addressPattern: 'orders.#',
  selector: 'customerEmail',
  dataClass: 'EMAIL',
  dataClassLabel: 'email',
  defaultAction: 'REDACT',
  builtin: false,
};

function mockApis(permissions: string[]) {
  server.use(
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        id: 'u1',
        username: 'ada',
        mustChangePassword: false,
        grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions }],
      }),
    ),
    http.get('*/api/v1/clusters', () => HttpResponse.json([])),
    http.get('*/api/v1/governance/rules', () => HttpResponse.json([BUILT_IN, CUSTOM])),
    http.get('*/api/v1/governance/remask', () =>
      HttpResponse.json({ version: 3, rowsUnderEarlierVersion: 1200, capped: false }),
    ),
  );
}

describe('RulesPanel', () => {
  it('lists rules and states that a built-in rule can be disabled but not deleted', async () => {
    mockApis(['governance:read', 'governance:write']);
    renderWithProviders(<RulesPanel />);

    expect(await screen.findByText('authorization')).toBeInTheDocument();
    expect(screen.getByText('Can be disabled, not deleted.')).toBeInTheDocument();
    expect(screen.getByText('Drop (default)')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Delete the rule for authorization' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Delete the rule for customerEmail' })).toBeEnabled();
    expect(screen.getByRole('switch', { name: 'Enabled: authorization' })).toBeEnabled();
    expect(await screen.findByText(/1,200 stored messages are still masked under an earlier policy/)).toBeInTheDocument();
  });

  it('keeps change controls visible but disabled, with the reason, for a read-only user', async () => {
    mockApis(['governance:read']);
    renderWithProviders(<RulesPanel />);

    expect(await screen.findByText('Changing masking rules needs the governance:write permission.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'New rule' })).toBeDisabled();
    expect(screen.getByRole('switch', { name: 'Enabled: customerEmail' })).toBeDisabled();
  });

  it('validates the name on blur and posts the rule', async () => {
    mockApis(['governance:write']);
    let posted: Record<string, unknown> | null = null;
    server.use(
      http.post('*/api/v1/governance/rules', async ({ request }) => {
        posted = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ ...CUSTOM, id: 'n1', selector: 'customerName' }, { status: 201 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<RulesPanel />);

    await user.click(await screen.findByRole('button', { name: 'New rule' }));
    const name = await screen.findByRole('textbox', { name: /Name/ });
    await user.click(name);
    await user.tab();
    expect(await screen.findByText(/Enter the name the rule matches/)).toBeInTheDocument();

    await user.type(name, 'customerName');
    await user.type(screen.getByRole('textbox', { name: /Addresses/ }), 'orders.#');
    await user.click(screen.getByRole('button', { name: 'Save rule' }));

    await waitFor(() => expect(posted).not.toBeNull());
    expect(posted).toMatchObject({ selector: 'customerName', addressPattern: 'orders.#', target: 'PROPERTY' });
    expect(await screen.findByText('Saved the rule for customerName.')).toBeInTheDocument();
  });

  it('focuses the first invalid field on a rejected save', async () => {
    mockApis(['governance:write']);
    const user = userEvent.setup();
    renderWithProviders(<RulesPanel />);

    await user.click(await screen.findByRole('button', { name: 'New rule' }));
    await user.click(await screen.findByRole('button', { name: 'Save rule' }));

    expect(screen.getByRole('textbox', { name: /Name/ })).toHaveFocus();
  });

  it('arms delete only on the typed name, and escape dismisses the dialog from the keyboard', async () => {
    mockApis(['governance:write']);
    const user = userEvent.setup();
    renderWithProviders(<RulesPanel />);

    const trigger = await screen.findByRole('button', { name: 'Delete the rule for customerEmail' });
    await user.click(trigger);
    const dialog = await screen.findByRole('dialog', { name: 'Delete the rule for customerEmail' });
    expect(dialog).toHaveTextContent('Values this rule masks become visible');
    expect(screen.getByRole('button', { name: 'Delete rule' })).toBeDisabled();

    await user.type(screen.getByRole('textbox', { name: /Type "customerEmail" to confirm/ }), 'customerEmail');
    expect(screen.getByRole('button', { name: 'Delete rule' })).toBeEnabled();

    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(trigger).toHaveFocus());
  });
});
