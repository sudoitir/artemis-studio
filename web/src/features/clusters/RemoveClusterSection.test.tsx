import { beforeEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

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

  it('states what goes and what stays before the typed name arms the button', async () => {
    renderWithProviders(<RemoveClusterSection clusterId={CLUSTER} />);

    expect(await screen.findByRole('button', { name: 'Remove cluster' })).toBeDisabled();
    expect(screen.getByText(/stored broker credentials/)).toBeInTheDocument();
    expect(screen.getByText(/Nothing on the broker changes/)).toBeInTheDocument();
  });

  it('removes the cluster by keyboard alone and leaves it', async () => {
    const user = userEvent.setup();
    const calls = deleteAnswers(204);
    renderWithProviders(<RemoveClusterSection clusterId={CLUSTER} />);

    const input = await screen.findByLabelText('Type "prod-eu" to confirm');
    await user.click(input);
    await user.keyboard('prod-eu');
    await user.tab();
    expect(screen.getByRole('button', { name: 'Remove cluster' })).toHaveFocus();
    await user.keyboard('{Enter}');

    await vi.waitFor(() => expect(navigateSpy).toHaveBeenCalledWith({ to: '/' }));
    expect(calls).toEqual([CLUSTER]);
  });

  it('states why a failed removal failed and stays', async () => {
    const user = userEvent.setup();
    deleteAnswers(409);
    renderWithProviders(<RemoveClusterSection clusterId={CLUSTER} />);

    await user.type(await screen.findByLabelText('Type "prod-eu" to confirm'), 'prod-eu');
    await user.click(screen.getByRole('button', { name: 'Remove cluster' }));

    expect(await screen.findByText('The cluster was not removed')).toBeInTheDocument();
    expect(screen.getByText(/It is still registered/)).toBeInTheDocument();
    expect(navigateSpy).not.toHaveBeenCalled();
  });

  it('says why removal is unavailable without the permission', async () => {
    grants(['cluster:read']);
    renderWithProviders(<RemoveClusterSection clusterId={CLUSTER} />);

    expect(await screen.findByText(/needs the/)).toBeInTheDocument();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Type "prod-eu" to confirm'), 'prod-eu');
    expect(screen.getByRole('button', { name: 'Remove cluster' })).toBeDisabled();
  });
});
