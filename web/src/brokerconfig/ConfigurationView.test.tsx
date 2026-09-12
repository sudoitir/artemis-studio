import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../test/render.tsx';
import { server } from '../test/setup.ts';
import { baseHandlers, cluster, declaration, NODE_A, NODE_B } from './fixtures.ts';

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

  it('opens the import drawer on the ledger snippet and merges it into the declaration by default', async () => {
    const snippet = '<address-setting match="#"><slow-consumer-threshold>1</slow-consumer-threshold></address-setting>';
    const withSnippet = {
      ...cluster(),
      capabilities: {
        ...cluster().capabilities,
        slowConsumerDetection: { status: 'UNKNOWN', reason: 'cannot tell', brokerXmlSnippet: snippet },
      },
    };
    server.use(
      // First match wins within one use(): the cluster override goes before the base handlers.
      http.get('*/api/v1/clusters/c1', () => HttpResponse.json(withSnippet)),
      ...baseHandlers(),
      http.post('*/api/v1/clusters/c1/config/import-xml', () =>
        HttpResponse.json({
          document: {
            version: 1,
            addresses: [],
            addressSettings: [{ match: '#', values: { slowConsumerThreshold: 1 } }],
            securitySettings: [],
            diverts: [],
          },
          unsupported: [],
          errors: [],
        }),
      ),
    );
    search.import = 'slowConsumerDetection';
    const user = userEvent.setup();
    renderWithProviders(<ConfigurationView />);

    const dialog = await screen.findByRole('dialog', { name: 'Import broker.xml' });
    expect(within(dialog).getByRole('textbox', { name: /broker\.xml/ })).toHaveValue(snippet);
    expect(within(dialog).getByRole('radio', { name: 'Merge into it' })).toBeChecked();
    // The hand-off parameter is consumed so a reload does not reopen the drawer.
    expect(search.import).toBeUndefined();

    await user.click(within(dialog).getByRole('button', { name: 'Preview import' }));
    // The fixture declaration already has orders.#; merging adds # and keeps it.
    await waitFor(() => expect(within(dialog).getByText('After merging')).toBeInTheDocument());
    expect(within(dialog).getByText(/Address settings: 2 recognised — 1 added, 0 changed, 1 unchanged/)).toBeInTheDocument();
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
});
