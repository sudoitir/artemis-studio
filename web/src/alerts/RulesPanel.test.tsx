import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../test/render.tsx';
import { server } from '../test/setup.ts';
import { RulesPanel } from './RulesPanel.tsx';

function rule(over: Record<string, unknown> = {}) {
  return {
    id: 'r1',
    clusterId: 'c1',
    name: 'Deep queue',
    kind: 'METRIC_THRESHOLD',
    metric: 'messageCount',
    comparator: 'GT',
    threshold: 1000,
    stateCondition: null,
    forSeconds: 60,
    severity: 'WARNING',
    scope: null,
    enabled: true,
    channelIds: [],
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...over,
  };
}

describe('RulesPanel', () => {
  it('lists existing rules and shows the threshold condition', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/alerts/rules', () => HttpResponse.json([rule()])),
      http.get('*/api/v1/channels', () => HttpResponse.json([])),
    );
    renderWithProviders(<RulesPanel clusterId="c1" />);

    expect(await screen.findByText('Deep queue')).toBeInTheDocument();
    expect(screen.getByText('messageCount > 1000')).toBeInTheDocument();
  });

  it('shows an empty state with no rules', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/alerts/rules', () => HttpResponse.json([])),
      http.get('*/api/v1/channels', () => HttpResponse.json([])),
    );
    renderWithProviders(<RulesPanel clusterId="c1" />);

    expect(await screen.findByText(/No rules yet/)).toBeInTheDocument();
  });

  it('switching the rule kind to state hides the metric fields and shows the state-condition select', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/alerts/rules', () => HttpResponse.json([])),
      http.get('*/api/v1/channels', () => HttpResponse.json([])),
    );
    const user = userEvent.setup();
    renderWithProviders(<RulesPanel clusterId="c1" />);

    await screen.findByText(/No rules yet/);
    expect(screen.getByRole('combobox', { name: 'Metric' })).toBeInTheDocument();

    await user.click(screen.getByRole('combobox', { name: 'Kind' }));
    await user.click(await screen.findByText('Cluster state'));

    expect(screen.queryByRole('combobox', { name: 'Metric' })).not.toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'State condition' })).toBeInTheDocument();
  });

  it('creates a threshold rule from the form', async () => {
    let created = false;
    server.use(
      http.get('*/api/v1/clusters/c1/alerts/rules', () =>
        HttpResponse.json(created ? [rule({ name: 'Deep queue' })] : []),
      ),
      http.get('*/api/v1/channels', () => HttpResponse.json([])),
      http.post('*/api/v1/clusters/c1/alerts/rules', () => {
        created = true;
        return HttpResponse.json(rule(), { status: 201 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<RulesPanel clusterId="c1" />);

    await screen.findByText(/No rules yet/);
    await user.type(screen.getByLabelText('Name'), 'Deep queue');
    await user.click(screen.getByRole('combobox', { name: 'Metric' }));
    await user.click(await screen.findByText('messageCount (gauge)'));
    await user.clear(screen.getByLabelText('Threshold'));
    await user.type(screen.getByLabelText('Threshold'), '1000');
    await user.click(screen.getByRole('button', { name: 'Add rule' }));

    expect(await screen.findByText('Deep queue')).toBeInTheDocument();
  });
});
