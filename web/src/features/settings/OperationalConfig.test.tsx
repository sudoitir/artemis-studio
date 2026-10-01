import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { Notifications, notifications } from '@mantine/notifications';
import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { OperationalConfig } from './OperationalConfig.tsx';

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

function mockMe(permissions: string[]) {
  server.use(
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        id: 'u1',
        username: 'ops',
        mustChangePassword: false,
        grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions }],
      }),
    ),
  );
}

function renderConfig() {
  return renderWithProviders(
    <>
      <Notifications />
      <OperationalConfig />
    </>,
  );
}

afterEach(() => act(() => notifications.clean()));

describe('OperationalConfig', () => {
  beforeEach(() => mockMe(['settings:write']));

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
    renderConfig();

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
            'metric.partition-maintainer-cron': setting({
              group: 'Retention',
              label: 'Partition maintainer schedule',
              value: '0 0 3 * * *',
              defaultValue: '0 0 3 * * *',
              kind: 'CRON',
            }),
            'scrape.tier-b-interval': setting({ label: 'Tier B interval', value: '15s' }),
          },
        }),
      ),
    );
    renderConfig();

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
    renderConfig();

    expect(await screen.findByText(/overridden — default is 5s/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reset' })).toBeInTheDocument();
  });

  it('renders an on/off setting as a switch that saves when flipped', async () => {
    let saved: unknown = null;
    server.use(
      http.get('*/api/v1/settings', () =>
        HttpResponse.json({
          settings: {
            'mcp.read-only': setting({
              label: 'Read-only',
              hint: 'No key can run a mutating tool.',
              group: 'Agent surface',
              value: 'false',
              defaultValue: 'false',
              kind: 'BOOLEAN',
            }),
          },
        }),
      ),
      http.put('*/api/v1/settings/mcp.read-only', async ({ request }) => {
        saved = await request.json();
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    renderConfig();

    await user.click(await screen.findByRole('switch', { name: /Read-only/ }));

    await expect.poll(() => saved).toEqual({ value: 'true' });
  });

  it('announces a saved value, and says why and what to do when saving fails', async () => {
    let fail = false;
    server.use(
      http.get('*/api/v1/settings', () => HttpResponse.json({ settings: { 'scrape.tier-a-interval': setting() } })),
      http.put('*/api/v1/settings/scrape.tier-a-interval', () =>
        fail
          ? HttpResponse.json({ title: 'Invalid', detail: 'Not a duration.' }, { status: 422 })
          : new HttpResponse(null, { status: 204 }),
      ),
    );
    const user = userEvent.setup();
    renderConfig();

    const field = await screen.findByLabelText('Tier A interval');
    await user.clear(field);
    await user.type(field, '10s');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Saved Tier A interval');

    fail = true;
    await user.clear(screen.getByLabelText('Tier A interval'));
    await user.type(screen.getByLabelText('Tier A interval'), 'soon');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not save Tier A interval');
    expect(alert).toHaveTextContent('Not a duration. It is still 5s. Try again.');
  });

  it('asks for a whole number beside the field and puts focus there, and keeps Save enabled', async () => {
    server.use(
      http.get('*/api/v1/settings', () =>
        HttpResponse.json({ settings: { 'k.n': setting({ label: 'Retention days', kind: 'INT', value: '7' }) } }),
      ),
    );
    const user = userEvent.setup();
    renderConfig();

    const field = await screen.findByLabelText('Retention days');
    await user.clear(field);
    await user.type(field, 'many');
    await user.tab();
    expect(await screen.findByText('Enter a whole number.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(field).toHaveFocus();
  });

  it('says nothing was saved when the value is unchanged', async () => {
    server.use(http.get('*/api/v1/settings', () => HttpResponse.json({ settings: { 'a.b': setting() } })));
    const user = userEvent.setup();
    renderConfig();

    await user.click(await screen.findByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Nothing to save: the value is unchanged.')).toBeInTheDocument();
  });

  it('keeps the controls visible but disabled for a reader, with the reason', async () => {
    mockMe(['settings:read']);
    server.use(http.get('*/api/v1/settings', () => HttpResponse.json({ settings: { 'a.b': setting() } })));
    renderConfig();

    expect(await screen.findByText('Changing settings needs the settings:write permission.')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled());
    expect(screen.getByLabelText('Tier A interval')).toBeDisabled();
  });

  it('states a failed load with its cause and offers a retry', async () => {
    server.use(
      http.get('*/api/v1/settings', () =>
        HttpResponse.json({ title: 'Boom', detail: 'The database is down.' }, { status: 500 }),
      ),
    );
    renderConfig();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The database is down.');
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('holds its place while the settings load, as a status', () => {
    server.use(http.get('*/api/v1/settings', () => new Promise(() => {})));
    renderConfig();

    expect(screen.getByRole('status')).toHaveTextContent('Loading settings');
  });
});
