import { beforeEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { paged } from '../../kernel/api/paging.ts';
import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';

const navigateSpy = vi.fn();
vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useNavigate: () => navigateSpy,
}));

const { RemoveClusterSection } = await import('./RemoveClusterSection.tsx');

const CLUSTER = '11111111-1111-1111-1111-111111111111';

function grants(permissions: string[]) {
  server.use(
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        id: 'u1',
        username: 'admin',
        mustChangePassword: false,
        grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions }],
      }),
    ),
  );
}

function deleteAnswers(status: number) {
  const calls: string[] = [];
  server.use(
    http.delete('*/api/v1/clusters/:id', ({ params }) => {
      calls.push(String(params.id));
      return status === 204
        ? new HttpResponse(null, { status: 204 })
        : HttpResponse.json({ title: 'Conflict', detail: 'The cluster is locked by a running transfer.' }, { status });
    }),
  );
  return calls;
}

describe('RemoveClusterSection', () => {
  beforeEach(() => {
    navigateSpy.mockReset();
    server.use(http.get('*/api/v1/clusters/:id', () => HttpResponse.json({ id: CLUSTER, name: 'prod-eu' })));
    grants(['cluster:read', 'cluster:write']);
  });

  it('names the environment beside the name to type', async () => {
    grants(['cluster:read', 'cluster:write', 'environment:read']);
    server.use(
      http.get('*/api/v1/clusters/:id', () =>
        HttpResponse.json({ id: CLUSTER, name: 'prod-eu', environmentId: 'e-live' }),
      ),
      http.get('*/api/v1/environments', () =>
        HttpResponse.json(paged([{ id: 'e-live', name: 'Production', colour: null, sortOrder: 1 }])),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<RemoveClusterSection clusterId={CLUSTER} />);

    await user.click(await screen.findByRole('button', { name: 'Remove cluster…' }));
    const dialog = await screen.findByRole('dialog', { name: 'Remove cluster' });
    expect(await within(dialog).findByText('In Production')).toBeInTheDocument();
  });

  it('states what goes and what stays before the typed name arms the button', async () => {
    const user = userEvent.setup();
    renderWithProviders(<RemoveClusterSection clusterId={CLUSTER} />);

    expect(await screen.findByText(/stored broker credentials/)).toBeInTheDocument();
    expect(screen.getByText(/Nothing on the broker changes/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Remove cluster…' }));

    const dialog = await screen.findByRole('dialog', { name: 'Remove cluster' });
    expect(dialog).toHaveTextContent('Nothing on the broker changes');
    expect(within(dialog).getByRole('button', { name: 'Remove cluster' })).toBeDisabled();
  });

  it('removes the cluster by keyboard alone, and focus returns to the button when the dialog is dismissed', async () => {
    const user = userEvent.setup();
    const calls = deleteAnswers(204);
    renderWithProviders(<RemoveClusterSection clusterId={CLUSTER} />);

    const trigger = await screen.findByRole('button', { name: 'Remove cluster…' });
    trigger.focus();
    await user.keyboard('{Enter}');
    const dialog = await screen.findByRole('dialog', { name: 'Remove cluster' });
    await vi.waitFor(() => expect(dialog).toContainElement(document.activeElement as HTMLElement));

    await user.keyboard('{Escape}');
    await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await vi.waitFor(() => expect(trigger).toHaveFocus());

    await user.keyboard('{Enter}');
    const again = await screen.findByRole('dialog', { name: 'Remove cluster' });
    await user.keyboard('prod-eu');
    expect(within(again).getByRole('button', { name: 'Remove cluster' })).toBeEnabled();
    // Enter in the armed name field confirms.
    await user.keyboard('{Enter}');

    await vi.waitFor(() => expect(navigateSpy).toHaveBeenCalledWith({ to: '/' }));
    expect(calls).toEqual([CLUSTER]);
  });

  it('states why a failed removal failed, keeps the dialog open and does not leave', async () => {
    const user = userEvent.setup();
    deleteAnswers(409);
    renderWithProviders(<RemoveClusterSection clusterId={CLUSTER} />);

    await user.click(await screen.findByRole('button', { name: 'Remove cluster…' }));
    const dialog = await screen.findByRole('dialog', { name: 'Remove cluster' });
    await user.type(within(dialog).getByLabelText('Type "prod-eu" to confirm'), 'prod-eu');
    await user.click(within(dialog).getByRole('button', { name: 'Remove cluster' }));

    expect(await within(dialog).findByText('The cluster is locked by a running transfer.')).toBeInTheDocument();
    expect(within(dialog).getByText(/It is still registered/)).toBeInTheDocument();
    expect(navigateSpy).not.toHaveBeenCalled();
  });

  it('says why removal is unavailable without the permission, and leaves the button visible but off', async () => {
    grants(['cluster:read']);
    renderWithProviders(<RemoveClusterSection clusterId={CLUSTER} />);

    expect(await screen.findByText(/needs the/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Remove cluster…' })).toBeDisabled();
  });

  it('holds its place while the cluster loads, and says why when it cannot be read', async () => {
    server.use(
      http.get('*/api/v1/clusters/:id', () =>
        HttpResponse.json({ title: 'Error', detail: 'The cluster store is down.' }, { status: 500 }),
      ),
    );
    renderWithProviders(<RemoveClusterSection clusterId={CLUSTER} />);

    expect(screen.getByText('Loading the cluster')).toBeInTheDocument();
    expect(await screen.findByText('The cluster store is down.')).toBeInTheDocument();
  });
});
