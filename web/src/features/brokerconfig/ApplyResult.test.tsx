import { describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import type { ConfigApplyOutcomeView, ConfigNodeApplyView, ConfigStepApplyView } from './api.ts';
import { halted, plan } from './fixtures.ts';

vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  Link: ({ children, to, ...rest }: { children: React.ReactNode; to: string }) => (
    <a href={to} {...rest}>
      {children}
    </a>
  ),
}));

const { ApplyResult } = await import('./ApplyResult.tsx');

const step = (over: Partial<ConfigStepApplyView> = {}): ConfigStepApplyView => ({
  stepId: 'ADDRESS_SETTING:orders.#:REPLACE',
  section: 'ADDRESS_SETTING',
  key: 'orders.#',
  op: 'REPLACE',
  description: 'Replace address setting orders.#',
  status: 'APPLIED',
  verified: 'VERIFIED',
  error: null,
  ...over,
});

const node = (over: Partial<ConfigNodeApplyView> = {}): ConfigNodeApplyView => ({
  nodeId: 'n-a',
  nodeName: 'broker-1',
  live: true,
  canary: false,
  unavailableReason: null,
  steps: [step()],
  note: null,
  ...over,
});

/** The fixture plan with the nodes replaced, so the verdict and rows follow what the test states. */
function outcome(nodes: ConfigNodeApplyView[], over: Partial<ConfigApplyOutcomeView> = {}): ConfigApplyOutcomeView {
  return plan({ dryRun: false, outcome: 'APPLIED', summary: 'Done.', nodes, ...over });
}

const status = () => screen.getByRole('status');

describe('ApplyResult verdict and per-node summary', () => {
  it('previews a plan as what would run, canary first, with each step before and after', () => {
    renderWithProviders(<ApplyResult outcome={plan()} />);

    expect(status()).toHaveTextContent('Would apply 2 steps to 2 live nodes, canary first');
    expect(within(status()).getByText('broker-1 (canary)')).toBeInTheDocument();
    expect(within(status()).getAllByText('1 step would apply')).toHaveLength(2);
    // A preview carries no result sentence.
    expect(screen.queryByText('Would apply 2 steps to 2 live nodes.')).toBeNull();
    // Only the keys that change are marked; the unchanged ones stay, dimmed.
    const change = screen.getAllByText('addressFullMessagePolicy')[0].closest('div')!;
    expect(change).toHaveTextContent('PAGE → DROP');
    expect(screen.getAllByText('broker-1 — canary')).toHaveLength(1);
  });

  it('states a preview of nothing to do, and names nodes that are not live', () => {
    const p = plan();
    const nodes = [
      node({ steps: [step({ status: 'ALREADY' })] }),
      node({ nodeId: 'n-b', nodeName: 'broker-2', live: false, unavailableReason: 'node is down', steps: [] }),
    ];
    renderWithProviders(<ApplyResult outcome={{ ...p, plan: { ...p.plan, stepCount: 0 }, nodes }} />);

    expect(status()).toHaveTextContent(
      'Nothing to do: every targeted node already matches revision 3 · 1 not live, will inherit through replication',
    );
    expect(within(status()).getByText('skipped — not live')).toBeInTheDocument();
    expect(within(status()).getByText('node is down')).toBeInTheDocument();
    expect(within(status()).getByText('already as declared')).toBeInTheDocument();
  });

  it('counts a single step and a single live node in the singular', () => {
    const p = plan();
    renderWithProviders(<ApplyResult outcome={{ ...p, plan: { ...p.plan, stepCount: 1 }, nodes: [p.nodes[0]] }} />);
    expect(status()).toHaveTextContent('Would apply 1 step to 1 live node, canary first');
  });

  it('reports a full apply as applied and verified, with the audit event linked', () => {
    renderWithProviders(
      <ApplyResult
        clusterId="c1"
        outcome={outcome([node({ canary: true }), node({ nodeId: 'n-b', nodeName: 'broker-2' })])}
      />,
    );

    expect(status()).toHaveTextContent('Applied to all 2 live nodes');
    expect(within(status()).getAllByText('1 applied and verified')).toHaveLength(2);
    expect(screen.getByText('Done.')).toBeInTheDocument();
    expect(screen.getByText(/Recorded as audit event 10/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'open the audit log' })).toHaveAttribute(
      'href',
      '/clusters/c1/audit?action=APPLY_BROKER_CONFIG',
    );
  });

  it('omits the audit link without a cluster, for a preview, and when no event was recorded', () => {
    const { rerender } = renderWithProviders(<ApplyResult outcome={outcome([node()])} />);
    expect(screen.queryByRole('link', { name: 'open the audit log' })).toBeNull();

    rerender(<ApplyResult clusterId="c1" outcome={outcome([node()], { auditEventId: null })} />);
    expect(screen.queryByRole('link', { name: 'open the audit log' })).toBeNull();

    rerender(<ApplyResult clusterId="c1" outcome={plan()} />);
    expect(screen.queryByRole('link', { name: 'open the audit log' })).toBeNull();
  });

  it('opens a halt on the node that failed and says what was not attempted', () => {
    renderWithProviders(<ApplyResult clusterId="c1" outcome={halted()} />);

    expect(status()).toHaveTextContent('Halted — applied to some nodes and not others');
    expect(within(status()).getByText('failed at step 1 of 1')).toBeInTheDocument();
    // The failure's own words appear under its node and again on the step.
    expect(within(status()).getByText('AMQ229001: invalid JSON')).toBeInTheDocument();
    expect(within(status()).getByText('not attempted')).toBeInTheDocument();
    expect(screen.getAllByText('AMQ229001: invalid JSON')).toHaveLength(2);
    expect(screen.getByText(/Nothing was rolled back/)).toBeInTheDocument();
  });

  it('reports a failed apply as applying nothing, and passes an unknown outcome through', () => {
    const { rerender } = renderWithProviders(
      <ApplyResult
        outcome={outcome([node({ steps: [step({ status: 'FAILED', error: 'refused' })] })], { outcome: 'FAILED' })}
      />,
    );
    expect(status()).toHaveTextContent('Failed — nothing was applied');

    rerender(
      <ApplyResult outcome={outcome([node()], { outcome: 'ROLLED_BACK' as ConfigApplyOutcomeView['outcome'] })} />,
    );
    expect(status()).toHaveTextContent('ROLLED_BACK');
  });

  it.each([
    ['a read-back that differs', [step({ verified: 'MISMATCH' })], 'applied — read back differs'],
    [
      'steps not attempted after some were applied',
      [step(), step({ stepId: 's2', key: 'other', status: 'NOT_ATTEMPTED', verified: 'NOT_VERIFIED' })],
      '1 applied, 1 not attempted',
    ],
    ['a node where every step is already as declared', [step({ status: 'ALREADY' })], 'already as declared'],
    [
      'applied steps beside ones already as declared',
      [step(), step({ stepId: 's2', key: 'other', status: 'ALREADY' })],
      '1 applied and verified, 1 already',
    ],
    [
      'a preview beside steps already as declared',
      [
        step({ status: 'WOULD_APPLY', verified: 'NOT_VERIFIED' }),
        step({ stepId: 's2', key: 'other', status: 'ALREADY' }),
      ],
      '1 step would apply, 1 already as declared',
    ],
  ])('words %s', (_name, steps, expected) => {
    renderWithProviders(<ApplyResult outcome={outcome([node({ steps })])} />);
    expect(within(status()).getByText(expected)).toBeInTheDocument();
  });

  it('states a node that failed with only its own note when the step gave no error', () => {
    renderWithProviders(
      <ApplyResult
        outcome={outcome([node({ note: 'connection dropped', steps: [step({ status: 'FAILED', error: null })] })])}
      />,
    );
    expect(within(status()).getByText('connection dropped')).toBeInTheDocument();
  });
});

describe('ApplyResult step tables', () => {
  const twoKeys = () =>
    outcome([
      node({
        canary: true,
        steps: [
          step(),
          step({
            stepId: 'SECURITY_SETTING:orders.#:ADD',
            section: 'SECURITY_SETTING',
            key: 'sec',
            op: 'ADD',
            description: 'Add security setting sec',
          }),
          step({
            stepId: 'DIVERT:d:ADD',
            section: 'DIVERT',
            key: 'd',
            op: 'ADD',
            description: 'Add divert d',
            status: 'ALREADY',
          }),
        ],
      }),
    ]);

  it('folds away the steps already as declared, counted, until asked', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ApplyResult outcome={twoKeys()} />);

    expect(screen.queryByText('Add divert d')).toBeNull();
    expect(screen.getByText('2 of 3 steps shown')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Show the 1 already as declared' }));

    expect(screen.getByText('Add divert d')).toBeInTheDocument();
    expect(screen.getByText('3 steps')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Hide the 1 already as declared' }));
    expect(screen.queryByText('Add divert d')).toBeNull();
  });

  it('filters by section, keeps step numbers from the whole plan, and clears the filter', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ApplyResult outcome={twoKeys()} />);

    expect(screen.queryByRole('button', { name: 'Clear the filter' })).toBeNull();
    await user.click(screen.getByRole('checkbox', { name: 'security setting' }));

    expect(screen.getByText('1 of 3 steps shown')).toBeInTheDocument();
    expect(screen.queryByText('Replace address setting orders.#')).toBeNull();
    // The number is the step's place in the plan, the one a halt message names.
    expect(within(screen.getByRole('row', { name: /Add security setting sec/ })).getByText('2')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Clear the filter' }));
    expect(screen.getByText('Replace address setting orders.#')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Clear the filter' })).toBeNull();
  });

  it('says why a node shows no step rather than dropping it: nothing matches the filter', async () => {
    const user = userEvent.setup();
    const two = outcome([
      node({ canary: true }),
      node({
        nodeId: 'n-b',
        nodeName: 'broker-2',
        steps: [step({ stepId: 's', section: 'DIVERT', key: 'd', op: 'ADD', description: 'Add divert d' })],
      }),
    ]);
    renderWithProviders(<ApplyResult outcome={two} />);

    await user.click(screen.getByRole('checkbox', { name: 'divert' }));
    expect(screen.getByText('broker-1: none of its 1 step match the filter.')).toBeInTheDocument();
    expect(screen.getByText('Add divert d')).toBeInTheDocument();
  });

  it('says a node with nothing to show has every step already as declared', () => {
    const p = outcome([
      node({ steps: [step({ status: 'ALREADY' }), step({ stepId: 's2', key: 'k2', status: 'ALREADY' })] }),
    ]);
    renderWithProviders(<ApplyResult outcome={p} />);
    expect(screen.getByText('broker-1: all 2 steps are already as declared.')).toBeInTheDocument();

    // and a single one is in the singular
    const single = outcome([node({ steps: [step({ status: 'ALREADY' })] })]);
    renderWithProviders(<ApplyResult outcome={single} />);
    expect(screen.getByText('broker-1: all 1 step is already as declared.')).toBeInTheDocument();
  });

  it('narrows to the key drift hands over, and shows everything once the filter is cleared', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ApplyResult outcome={twoKeys()} focus="sec" />);

    expect(screen.getByText('Add security setting sec')).toBeInTheDocument();
    expect(screen.queryByText('Replace address setting orders.#')).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Clear the filter' }));
    expect(screen.getByText('Replace address setting orders.#')).toBeInTheDocument();
  });

  it('opens the canary and any failed node of a large plan, and leaves the rest folded', () => {
    const many = outcome([
      node({ nodeId: 'n-a', nodeName: 'broker-1', canary: true, steps: [step({ description: 'canary step' })] }),
      node({ nodeId: 'n-b', nodeName: 'broker-2', steps: [step({ description: 'quiet step' })] }),
      node({
        nodeId: 'n-c',
        nodeName: 'broker-3',
        steps: [step({ description: 'failed step', status: 'FAILED', error: 'boom' })],
      }),
    ]);
    renderWithProviders(<ApplyResult outcome={many} />);

    expect(screen.getByRole('button', { name: /broker-1 — canary/ })).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('button', { name: /broker-2/ })).toHaveAttribute('aria-expanded', 'false');
    expect(screen.getByRole('button', { name: /broker-3/ })).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText('failed step')).toBeVisible();
  });

  it('renders how each step changes: a create as its values, an update as before to after, and no plan as a dash', () => {
    const p = plan();
    const create = {
      ...p.plan.nodes[0].steps[0],
      id: 'create',
      op: 'ADD' as const,
      before: {},
      after: { a: 1, list: ['x', 'y'], none: [], obj: { k: 1 } },
    };
    const update = {
      ...p.plan.nodes[0].steps[0],
      id: 'update',
      before: { same: 'v', gone: true, n: 1 },
      after: { same: 'v', n: 2 },
    };
    const empty = { ...p.plan.nodes[0].steps[0], id: 'empty', before: {}, after: {} };
    const o = outcome(
      [
        node({
          steps: [
            step({ stepId: 'create', key: 'k1', description: 'create step' }),
            step({ stepId: 'update', key: 'k2', description: 'update step' }),
            step({ stepId: 'empty', key: 'k3', description: 'empty step' }),
            step({ stepId: 'unplanned', key: 'k4', description: 'unplanned step' }),
          ],
        }),
      ],
      { plan: { ...p.plan, nodes: [{ ...p.plan.nodes[0], steps: [create, update, empty] }] } },
    );
    renderWithProviders(<ApplyResult outcome={o} />);

    const row = (name: RegExp) => screen.getByRole('row', { name });
    expect(row(/create step/)).toHaveTextContent('a1');
    expect(row(/create step/)).toHaveTextContent('listx,y');
    expect(row(/create step/)).toHaveTextContent('none—');
    expect(row(/create step/)).toHaveTextContent('obj{"k":1}');
    expect(row(/create step/)).not.toHaveTextContent('→');
    // The keys that move come first, before → after; a key that vanished shows its old value.
    expect(row(/update step/)).toHaveTextContent('gonetrue → —');
    expect(row(/update step/)).toHaveTextContent('n1 → 2');
    expect(row(/update step/)).toHaveTextContent('samev');
    expect(row(/update step/).textContent!.indexOf('gone')).toBeLessThan(
      row(/update step/).textContent!.indexOf('same'),
    );
    expect(within(row(/empty step/)).getAllByText('—').length).toBeGreaterThan(0);
    expect(within(row(/unplanned step/)).getAllByText('—').length).toBeGreaterThan(0);
  });

  it('names each node’s steps as a table whose number heads the row, one heading per node', () => {
    renderWithProviders(<ApplyResult outcome={halted()} clusterId="c1" />);

    const table = screen.getByRole('table', { name: 'Steps on broker-1' });
    expect(within(table).getByRole('rowheader', { name: '1' })).toBeInTheDocument();
    for (const header of ['#', 'Step', 'Change', 'Status']) {
      expect(within(table).getByRole('columnheader', { name: header })).toBeInTheDocument();
    }
    // A step's status is a word in its own cell, and its error is under it.
    expect(within(table).getByText('failed')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 4, name: /broker-1/ })).toBeInTheDocument();
  });

  it('draws the before and after of a step as terms and values, with no key and value table', () => {
    renderWithProviders(<ApplyResult outcome={plan()} />);

    const term = screen.getAllByText('addressFullMessagePolicy')[0];
    expect(term.tagName).toBe('DT');
    expect(term.nextElementSibling).toHaveTextContent('PAGE → DROP');
    expect(screen.getAllByRole('table').every((t) => t.getAttribute('aria-label')?.startsWith('Steps on'))).toBe(true);
  });

  it('puts the filter’s toggles on buttons, not on text that looks like links', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ApplyResult outcome={twoKeys()} />);

    const toggle = screen.getByRole('button', { name: 'Show the 1 already as declared' });
    expect(toggle.tagName).toBe('BUTTON');
    await user.click(toggle);
    expect(screen.getByRole('button', { name: 'Hide the 1 already as declared' })).toBeInTheDocument();
  });
});
