import { beforeEach, describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { RulesPanel } from './RulesPanel.tsx';
import { paged } from '../../kernel/api/paging.ts';

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
    sourceAvailable: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...over,
  };
}

describe('RulesPanel', () => {
  beforeEach(() => {
    server.use(http.get('*/api/v1/clusters/c1/alerts/plugin-metrics', () => HttpResponse.json(paged([]))));
  });

  it('offers the metrics running plugins publish and describes the chosen one', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/alerts/rules', () => HttpResponse.json(paged([]))),
      http.get('*/api/v1/channels', () => HttpResponse.json(paged([]))),
      http.get('*/api/v1/clusters/c1/alerts/plugin-metrics', () =>
        HttpResponse.json(
          paged([
            {
              metric: 'acme-notes:edits',
              plugin: 'acme-notes',
              description: 'Edits per note.',
              unit: 'count',
              subject: 'note',
            },
          ]),
        ),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<RulesPanel clusterId="c1" />);

    await screen.findByText(/No rules yet/);
    await user.click(screen.getByRole('combobox', { name: 'Metric' }));
    await user.click(await screen.findByText('acme-notes:edits (count, per note)'));

    expect(screen.getByText(/Edits per note\. Published by the acme-notes plugin/)).toBeInTheDocument();
  });

  it('says when a rule watches a metric whose plugin is not running', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/alerts/rules', () =>
        HttpResponse.json(paged([rule({ metric: 'acme-notes:edits', sourceAvailable: false })])),
      ),
      http.get('*/api/v1/channels', () => HttpResponse.json(paged([]))),
    );
    renderWithProviders(<RulesPanel clusterId="c1" />);

    expect(await screen.findByText(/Source unavailable/)).toBeInTheDocument();
  });

  it('lists existing rules and shows the threshold condition', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/alerts/rules', () => HttpResponse.json(paged([rule()]))),
      http.get('*/api/v1/channels', () => HttpResponse.json(paged([]))),
    );
    renderWithProviders(<RulesPanel clusterId="c1" />);

    expect(await screen.findByText('Deep queue')).toBeInTheDocument();
    expect(screen.getByText('messageCount > 1000')).toBeInTheDocument();
  });

  it('shows an empty state with no rules', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/alerts/rules', () => HttpResponse.json(paged([]))),
      http.get('*/api/v1/channels', () => HttpResponse.json(paged([]))),
    );
    renderWithProviders(<RulesPanel clusterId="c1" />);

    expect(await screen.findByText(/No rules yet/)).toBeInTheDocument();
  });

  it('switching the rule kind to state hides the metric fields and shows the state-condition select', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/alerts/rules', () => HttpResponse.json(paged([]))),
      http.get('*/api/v1/channels', () => HttpResponse.json(paged([]))),
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
        HttpResponse.json(paged(created ? [rule({ name: 'Deep queue' })] : [])),
      ),
      http.get('*/api/v1/channels', () => HttpResponse.json(paged([]))),
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

  it('marks an installation rule and keeps a cluster rule unmarked', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/alerts/rules', () =>
        HttpResponse.json(
          paged([
            rule(),
            rule({
              id: 'r2',
              clusterId: null,
              name: 'Quota watch',
              kind: 'STATE',
              metric: null,
              comparator: null,
              threshold: null,
              stateCondition: 'STORAGE_QUOTA',
            }),
          ]),
        ),
      ),
      http.get('*/api/v1/channels', () => HttpResponse.json(paged([]))),
    );
    renderWithProviders(<RulesPanel clusterId="c1" />);

    expect(await screen.findByText('Quota watch')).toBeInTheDocument();
    expect(screen.getAllByText('Installation')).toHaveLength(1);
  });

  it('edits an installation rule on the storage conditions only', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/alerts/rules', () =>
        HttpResponse.json(
          paged([
            rule({
              id: 'r2',
              clusterId: null,
              name: 'Quota watch',
              kind: 'STATE',
              metric: null,
              comparator: null,
              threshold: null,
              stateCondition: 'STORAGE_QUOTA',
            }),
          ]),
        ),
      ),
      http.get('*/api/v1/channels', () => HttpResponse.json(paged([]))),
    );
    const user = userEvent.setup();
    renderWithProviders(<RulesPanel clusterId="c1" />);

    await user.click(await screen.findByRole('button', { name: 'Edit Quota watch' }));
    await user.click(screen.getByRole('combobox', { name: 'State condition' }));

    expect(await screen.findByText('storage health')).toBeInTheDocument();
    expect(screen.queryByText('node down')).not.toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Kind' })).toBeDisabled();
  });
});
