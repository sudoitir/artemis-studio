import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';

import { renderWithProviders } from '../test/render.tsx';
import { server } from '../test/setup.ts';
import { OperationalConfig } from './SettingsView.tsx';

function setting(over: Record<string, unknown> = {}) {
  return {
    value: '5s',
    overridden: false,
    defaultValue: '5s',
    group: 'Scrape',
    label: 'Tier A interval',
    hint: 'HA state, topology and split-brain corroboration.',
    kind: 'DURATION',
    ...over,
  };
}

describe('OperationalConfig', () => {
  /**
   * The point of the server-driven form: a key this file has never heard of still
   * renders, with the server's own label and hint. If this breaks, adding a setting
   * silently stops reaching the screen.
   */
  it('renders whatever the API describes, including unknown keys', async () => {
    server.use(
      http.get('*/api/v1/settings', () =>
        HttpResponse.json({
          settings: {
            'some.brand-new-key': setting({
              label: 'A key the frontend does not know',
              hint: 'Invented by the server.',
              group: 'Invented group',
              value: '12',
              defaultValue: '12',
              kind: 'INT',
            }),
          },
        }),
      ),
    );
    renderWithProviders(<OperationalConfig />);

    expect(await screen.findByText('Invented group')).toBeInTheDocument();
    expect(screen.getByLabelText('A key the frontend does not know')).toHaveValue('12');
    expect(screen.getByText('Invented by the server.')).toBeInTheDocument();
  });

  it('groups keys under their server-supplied section, in the order sent', async () => {
    server.use(
      http.get('*/api/v1/settings', () =>
        HttpResponse.json({
          settings: {
            'scrape.tier-a-interval': setting(),
            'metric.retention-days': setting({
              group: 'Retention',
              label: 'Metric retention (days)',
              value: '7',
              defaultValue: '7',
              kind: 'INT',
            }),
            'scrape.tier-b-interval': setting({ label: 'Tier B interval', value: '15s' }),
          },
        }),
      ),
    );
    renderWithProviders(<OperationalConfig />);

    expect(await screen.findByText('Scrape')).toBeInTheDocument();
    const groups = screen.getAllByText(/^(Scrape|Retention)$/).map((n) => n.textContent);
    expect(groups).toEqual(['Scrape', 'Retention']);
    // Both scrape keys land in the one Scrape section, not a second one.
    expect(screen.getAllByText('Scrape')).toHaveLength(1);
  });

  it('flags an override and offers a reset back to the packaged default', async () => {
    server.use(
      http.get('*/api/v1/settings', () =>
        HttpResponse.json({
          settings: {
            'scrape.tier-a-interval': setting({
              value: '30s',
              defaultValue: '5s',
              overridden: true,
            }),
          },
        }),
      ),
    );
    renderWithProviders(<OperationalConfig />);

    expect(await screen.findByText(/overridden — default is 5s/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reset' })).toBeInTheDocument();
  });
});
