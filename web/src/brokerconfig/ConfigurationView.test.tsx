import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../test/render.tsx';
import { server } from '../test/setup.ts';
import { baseHandlers, declaration, NODE_A, NODE_B } from './fixtures.ts';

const search: Record<string, unknown> = {};
vi.mock('@tanstack/react-router', () => ({
  useParams: () => ({ clusterId: 'c1' }),
  useSearch: () => search,
  useNavigate: () => (opts: { search?: (prev: Record<string, unknown>) => Record<string, unknown> }) => {
    if (opts.search) Object.assign(search, opts.search(search));
  },
  Link: ({ children, to, ...rest }: { children: React.ReactNode; to: string }) => (
    <a href={to} {...rest}>
      {children}
    </a>
  ),
}));

const { ConfigurationView } = await import('./ConfigurationView.tsx');

describe('ConfigurationView', () => {
  it('teaches what a declaration is when nothing is declared', async () => {
    server.use(
      ...baseHandlers(
        declaration({
          declared: false,
          revision: 0,
          document: { version: 1, addresses: [], addressSettings: [], securitySettings: [], diverts: [] },
          nodes: [],
        }),
      ),
    );
    renderWithProviders(<ConfigurationView />);

    expect(await screen.findByText(/Declare what this cluster should run/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Adopt from cluster' })).toBeEnabled();
    // The apply control is visible and explains itself — never hidden.
    expect(screen.getByRole('button', { name: 'Why this is unavailable' })).toBeInTheDocument();
  });

  it('disables apply with the reason when the cluster is managed outside Studio', async () => {
    server.use(...baseHandlers(declaration({ applyMode: 'CONFIG_MANAGED' })));
    const user = userEvent.setup();
    renderWithProviders(<ConfigurationView />);

    expect(await screen.findByText(/Managed outside Studio/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Why this is unavailable' }));
    expect(await screen.findByText(/owned by configuration management/)).toBeInTheDocument();
    // The primary action flipped to the fragment.
    expect(screen.getByRole('button', { name: 'Copy broker.xml fragment' })).toBeEnabled();
  });

  it('opens the address-setting editor on the house form pattern: blur validation and focus on the first invalid field', async () => {
    server.use(...baseHandlers());
    const user = userEvent.setup();
    search.section = 'addressSettings';
    renderWithProviders(<ConfigurationView />);

    await user.click(await screen.findByRole('button', { name: 'Add address setting' }));
    const dialog = await screen.findByRole('dialog', { name: 'New address setting' });
    const match = within(dialog).getByRole('textbox', { name: /match pattern/i });
    await user.click(match);
    await user.tab();
    expect(await within(dialog).findByText(/A match pattern is required/)).toBeInTheDocument();

    await user.click(within(dialog).getByRole('button', { name: /Save as revision 4/ }));
    expect(match).toHaveFocus();
    expect(within(dialog).getByText('Fix the fields above to continue.')).toBeInTheDocument();
    delete search.section;
  });

  it('states the drift as a sentence when every live node matches, and names the node when one does not', async () => {
    server.use(...baseHandlers());
    search.tab = 'drift';
    const { unmount } = renderWithProviders(<ConfigurationView />);
    expect(await screen.findByText('All 2 live nodes match revision 3.')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    unmount();

    server.use(
      ...baseHandlers(
        declaration({
          nodes: [
            NODE_A,
            {
              ...NODE_B,
              state: 'DRIFTED',
              findings: [
                {
                  kind: 'DIVERGENT',
                  section: 'ADDRESS_SETTING',
                  key: 'orders.#',
                  detail: 'Replace address setting orders.#',
                  declared: { addressFullMessagePolicy: 'PAGE' },
                  observed: { addressFullMessagePolicy: 'DROP' },
                },
              ],
            },
          ],
        }),
      ),
    );
    renderWithProviders(<ConfigurationView />);
    expect(await screen.findByText('2 live nodes: 1 drifted, 1 in sync.')).toBeInTheDocument();
    const table = await screen.findByRole('table');
    expect(within(table).getByText('Observed on broker-2')).toBeInTheDocument();
    // The key is shown by its broker.xml element name, from the catalogue, beside its value.
    expect(within(table).getAllByText('address-full-policy').length).toBe(2);
    expect(within(table).getByText('PAGE')).toBeInTheDocument();
    expect(within(table).getByText('DROP')).toBeInTheDocument();
    delete search.tab;
  });

  it('lists what an import cannot carry instead of dropping it', async () => {
    server.use(
      ...baseHandlers(),
      http.post('*/api/v1/clusters/c1/config/import-xml', () =>
        HttpResponse.json({
          document: { version: 1, addresses: [], addressSettings: [{ match: 'orders.#', values: {} }], securitySettings: [], diverts: [] },
          unsupported: [{ path: 'core/global-max-size', reason: 'a static setting; it cannot be applied over the management API' }],
          errors: [],
        }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<ConfigurationView />);

    await user.click(await screen.findByRole('button', { name: 'Import XML' }));
    const dialog = await screen.findByRole('dialog', { name: 'Import broker.xml' });
    await user.type(within(dialog).getByRole('textbox'), '<core></core>');
    await user.click(within(dialog).getByRole('button', { name: 'Preview import' }));

    await waitFor(() => expect(within(dialog).getByText('Not applied (1)')).toBeInTheDocument());
    expect(within(dialog).getByText('core/global-max-size')).toBeInTheDocument();
    expect(within(dialog).getByText(/cannot be applied over the management API/)).toBeInTheDocument();
  });

  it('recommends the settings the probe found, seeded from the node, and declares the chosen ones', async () => {
    const declared = vi.fn();
    server.use(
      ...baseHandlers(),
      http.get('*/api/v1/clusters/c1/config/recommendations', () =>
        HttpResponse.json({
          seededFrom: 'broker-1',
          recommendations: [
            {
              capability: 'slowConsumerDetection',
              title: 'Let the broker detect slow consumers',
              rationale: 'The broker sees each consumer’s own delivery rate.',
              appliable: true,
              section: 'ADDRESS_SETTING',
              match: '#',
              values: { maxDeliveryAttempts: 7, slowConsumerThreshold: 1 },
              roles: {},
              keys: ['slowConsumerThreshold'],
              manualSnippet: null,
            },
            {
              capability: 'notifications',
              title: 'Emit connection and session events',
              rationale: 'NotificationActiveMQServerPlugin is a broker-plugin.',
              appliable: false,
              section: null,
              match: null,
              values: {},
              roles: {},
              keys: [],
              manualSnippet: '<broker-plugins/>',
            },
          ],
        }),
      ),
      http.post('*/api/v1/clusters/c1/config/recommendations/declare', async ({ request }) => {
        declared((await request.json()) as unknown);
        return HttpResponse.json(declaration(), { status: 201 });
      }),
    );
    search.tab = 'recommended';
    const user = userEvent.setup();
    renderWithProviders(<ConfigurationView />);

    // The whole entry is shown, not only the key being changed: a runtime write
    // replaces the entry, so the keys it keeps are part of what is confirmed.
    expect(await screen.findAllByText('slowConsumerThreshold')).not.toHaveLength(0);
    expect(screen.getByText('maxDeliveryAttempts')).toBeInTheDocument();
    expect(screen.getByText('(unchanged)')).toBeInTheDocument();
    expect(screen.getByText(/as broker-1 runs it today/)).toBeInTheDocument();

    // A gap Studio cannot close is named with its snippet, never omitted.
    expect(screen.getByText('Emit connection and session events')).toBeInTheDocument();
    expect(screen.getByText(/These still need a broker.xml edit/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Declare & open the plan/ }));
    await waitFor(() => expect(declared).toHaveBeenCalled());
    expect(declared.mock.calls[0][0]).toMatchObject({ capabilities: ['slowConsumerDetection'] });
  });

  it('will not declare a security setting that would grant nobody anything', async () => {
    server.use(
      ...baseHandlers(),
      http.get('*/api/v1/clusters/c1/config/recommendations', () =>
        HttpResponse.json({
          seededFrom: null,
          recommendations: [
            {
              capability: 'notifications',
              title: 'Let Studio subscribe to broker notifications',
              rationale: 'A Core subscriber needs three permissions.',
              appliable: true,
              section: 'SECURITY_SETTING',
              match: 'activemq.notifications',
              values: {},
              roles: { consume: [], createNonDurableQueue: [] },
              keys: ['consume'],
              manualSnippet: null,
            },
          ],
        }),
      ),
    );
    search.tab = 'recommended';
    renderWithProviders(<ConfigurationView />);

    expect(await screen.findByText(/At least one role, or this grants nobody anything/)).toBeInTheDocument();
    // Disabled with the reason beside it, never hidden.
    expect(screen.getByRole('button', { name: /Declare & open the plan/ })).toBeDisabled();
    expect(screen.getByText(/would grant nobody anything/)).toBeInTheDocument();
    // No node could be read, so the panel says the entries are unseeded.
    expect(screen.getByText(/No node could be read/)).toBeInTheDocument();
  });

  it('says why an in-sync node agrees, and says so when nothing recorded it', async () => {
    server.use(
      ...baseHandlers(
        declaration({
          nodes: [
            { ...NODE_A, state: 'IN_SYNC', basis: 'ADOPTED', basisRef: 3 },
            { ...NODE_B, state: 'IN_SYNC', basis: null, basisRef: null },
          ],
        }),
      ),
    );
    search.tab = 'drift';
    const user = userEvent.setup();
    renderWithProviders(<ConfigurationView />);

    // Adoption and a verified apply both read "in sync"; only one of them means
    // Studio wrote anything, so the difference is on the screen.
    expect(await screen.findByRole('link', { name: /Adopted as revision 3; no broker was written/ })).toBeInTheDocument();
    expect(screen.getByText('No record of why it agrees.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Evaluate now' }));
    delete search.tab; // the mock's search object is shared across tests
  });

  it('makes an adoption that closes drift name what it erases and type the cluster to confirm', async () => {
    server.use(
      ...baseHandlers(),
      http.post('*/api/v1/clusters/c1/config/adopt', () =>
        HttpResponse.json({
          document: { version: 1, addresses: [], addressSettings: [{ match: 'orders.#', values: {} }], securitySettings: [], diverts: [] },
          notes: ['1 open drift finding(s) will be closed by adopting this document, and no broker will be written'],
          disagreements: [],
          closes: [
            {
              nodeId: 'n-b',
              nodeName: 'broker-2',
              finding: {
                kind: 'DIVERGENT',
                section: 'ADDRESS_SETTING',
                key: 'orders.#',
                detail: 'maxSizeBytes differs from the declaration',
                declared: {},
                observed: {},
              },
            },
          ],
        }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<ConfigurationView />);

    await user.click(await screen.findByRole('button', { name: 'Adopt from cluster' }));
    const dialog = await screen.findByRole('dialog', { name: 'Adopt from cluster' });

    expect(await within(dialog).findByText(/Closes 1 open drift finding with zero broker writes/)).toBeInTheDocument();
    expect(within(dialog).getByText(/broker-2: maxSizeBytes differs/)).toBeInTheDocument();

    // Saving is armed only by the cluster's name: the effect looks like an apply
    // and the meaning is its opposite.
    const save = within(dialog).getByRole('button', { name: 'Save as revision 4' });
    expect(save).toBeDisabled();
    await user.type(within(dialog).getByRole('textbox', { name: /Type "prod"/ }), 'prod');
    expect(save).toBeEnabled();
  });

  it('offers address-setting templates built from the cluster\'s own DLQ, and saves nothing until asked', async () => {
    server.use(
      ...baseHandlers(),
      http.get('*/api/v1/clusters/c1/dlq', () =>
        HttpResponse.json({
          settingsAvailable: true,
          addresses: [
            { address: 'ORDERS.DLQ', kind: 'dead-letter', queues: [] },
            { address: 'ORDERS.EXPIRY', kind: 'expiry', queues: [] },
          ],
        }),
      ),
    );
    const user = userEvent.setup();
    search.section = 'addressSettings';
    renderWithProviders(<ConfigurationView />);

    await user.click(await screen.findByRole('button', { name: 'Add address setting' }));
    const dialog = await screen.findByRole('dialog', { name: 'New address setting' });

    // The names come from the cluster, never invented: a prefilled DLQ that does
    // not exist declares a policy that routes nowhere.
    await user.click(await within(dialog).findByRole('checkbox', { name: 'Retry, then dead-letter' }));
    expect(within(dialog).getByRole('textbox', { name: /dead-letter-address/ })).toHaveValue('ORDERS.DLQ');
    // The other keys of the template land too; only the ones this fixture's
    // catalogue knows are rendered as fields.
    expect(within(dialog).getByText(/Fills the fields below from this cluster's own ORDERS.DLQ and ORDERS.EXPIRY/))
        .toBeInTheDocument();
    delete search.section;
  });
});
