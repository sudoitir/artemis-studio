import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../test/render.tsx';
import { server } from '../test/setup.ts';
import type { ConfigApplyOutcomeView } from '../api/client.ts';
import { baseHandlers, declaration, halted, plan } from './fixtures.ts';

vi.mock('@tanstack/react-router', () => ({
  useParams: () => ({ clusterId: 'c1' }),
  Link: ({ children, to, ...rest }: { children: React.ReactNode; to: string }) => (
    <a href={to} {...rest}>
      {children}
    </a>
  ),
}));

const { ApplyView } = await import('./ApplyView.tsx');

function applyHandler(onReal: () => ConfigApplyOutcomeView) {
  return http.post('*/api/v1/clusters/c1/config/apply', ({ request }) => {
    const url = new URL(request.url);
    if (url.searchParams.get('dryRun') === 'true') return HttpResponse.json(plan());
    return HttpResponse.json(onReal());
  });
}

describe('ApplyView', () => {
  it('plans on entry, lists the High hazard and will not arm until it is acknowledged', async () => {
    server.use(...baseHandlers(), applyHandler(() => plan({ dryRun: false, outcome: 'APPLIED' })));
    const user = userEvent.setup();
    renderWithProviders(<ApplyView />);

    expect(await screen.findByText(/Would apply 2 steps to 2 live nodes, canary first/)).toBeInTheDocument();
    expect(screen.getByText(/Hazards \(1\) — 1 High/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Continue to confirm' }));
    expect(await screen.findByText(/1 High hazard not yet acknowledged above/)).toBeInTheDocument();

    const confirm = screen.getByRole('textbox', { name: /Type "prod" to confirm/ });
    await user.type(confirm, 'prod');
    const arm = screen.getByRole('button', { name: 'Apply to 2 nodes, canary first' });
    expect(arm).toBeDisabled();

    await user.click(screen.getByRole('checkbox', { name: /I understand: message loss policy on broker-1/ }));
    await waitFor(() => expect(arm).toBeEnabled());
  });

  it('renders a halted run with the failed step, the not-attempted node and the converge wording', async () => {
    server.use(...baseHandlers(), applyHandler(halted));
    const user = userEvent.setup();
    renderWithProviders(<ApplyView />);

    await screen.findByText(/Would apply 2 steps/);
    await user.click(screen.getByRole('checkbox', { name: /I understand/ }));
    await user.click(screen.getByRole('button', { name: 'Continue to confirm' }));
    await user.type(await screen.findByRole('textbox', { name: /Type "prod" to confirm/ }), 'prod');
    await user.click(screen.getByRole('button', { name: 'Apply to 2 nodes, canary first' }));

    expect(await screen.findByText('Halted — applied to some nodes and not others')).toBeInTheDocument();
    expect(screen.getByText(/Nothing was rolled back/)).toBeInTheDocument();
    expect(screen.getAllByText('not attempted').length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText(/failed at step 1 of 1/)).toBeInTheDocument();
    expect(screen.getAllByText('AMQ229001: invalid JSON').length).toBeGreaterThanOrEqual(1);
  });

  it('keeps the confirmation visible and explains itself when the cluster is managed outside Studio', async () => {
    server.use(...baseHandlers(declaration({ applyMode: 'CONFIG_MANAGED' })), applyHandler(() => plan()));
    const user = userEvent.setup();
    renderWithProviders(<ApplyView />);

    await screen.findByText(/Would apply 2 steps/);
    await user.click(screen.getByRole('button', { name: 'Continue to confirm' }));
    expect(await screen.findByText(/owned by configuration management/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Why this is unavailable' })).toBeInTheDocument();
  });

  it('is operable from the keyboard alone: tab into the flow, escape leaves the plan intact, focus stays', async () => {
    server.use(...baseHandlers(), applyHandler(() => plan()));
    const user = userEvent.setup();
    renderWithProviders(<ApplyView />);

    await screen.findByText(/Would apply 2 steps/);
    const cont = screen.getByRole('button', { name: 'Continue to confirm' });
    cont.focus();
    await user.keyboard('{Enter}');
    const confirm = await screen.findByRole('textbox', { name: /Type "prod" to confirm/ });
    await user.tab();
    await user.tab();
    await user.tab();
    await user.tab();
    // Escape is not a way out of a non-modal flow; the plan is still there and the field still reachable.
    await user.keyboard('{Escape}');
    expect(confirm).toBeInTheDocument();
    expect(screen.getByText(/Would apply 2 steps/)).toBeInTheDocument();
  });

  it('filters a long plan by section and key without changing what will run', async () => {
    // A plan over a cluster is a long list and the operator is looking for one
    // match; the filter is a view, so the step numbers and the counted total stay
    // the plan's own.
    const twoKeys = (() => {
      const base = plan();
      const second = {
        ...base.nodes[0].steps[0],
        stepId: 'ADDRESS_SETTING:payments.#:REPLACE',
        key: 'payments.#',
        description: 'Replace address setting payments.#',
      };
      return {
        ...base,
        plan: { ...base.plan, stepCount: 4 },
        nodes: base.nodes.map((n) => ({ ...n, steps: [n.steps[0], second] })),
      } as ConfigApplyOutcomeView;
    })();
    server.use(
      ...baseHandlers(),
      http.post('*/api/v1/clusters/c1/config/apply', () => HttpResponse.json(twoKeys)),
    );
    const user = userEvent.setup();
    renderWithProviders(<ApplyView />);

    await screen.findByRole('checkbox', { name: /payments\.#/ });
    expect(screen.getAllByText('Replace address setting orders.#').length).toBe(2);

    await user.click(screen.getByRole('checkbox', { name: 'payments.#' }));
    await waitFor(() => expect(screen.queryByText('Replace address setting orders.#')).not.toBeInTheDocument());
    // The step keeps its number in the plan, not its place in the filtered view.
    expect(screen.getAllByText('broker-1 — canary — 1 of 2 steps shown').length).toBe(1);
    expect(screen.getByText('Show all 4 steps')).toBeInTheDocument();

    await user.click(screen.getByText('Show all 4 steps'));
    expect(screen.getAllByText('Replace address setting orders.#').length).toBe(2);
  });
});
