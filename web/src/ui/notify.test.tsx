import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, screen, waitFor } from '@testing-library/react';
import { Notifications, notifications } from '@mantine/notifications';

import { renderWithProviders } from '../test/render.tsx';
import { notify, type ActionVerb } from './notify.ts';

const DELETE: ActionVerb = { verb: 'Delete', past: 'Deleted', progressive: 'Deleting' };
const notice = { action: DELETE, subject: 'queue "orders"' };

function mount() {
  return renderWithProviders(<Notifications />);
}

describe('notify', () => {
  afterEach(() => {
    act(() => notifications.clean());
    vi.useRealTimers();
  });

  it('names the pending state and the success with the action verb, both polite', async () => {
    mount();
    let id = '';
    act(() => {
      id = notify.pending(notice);
    });
    expect(await screen.findByRole('status')).toHaveTextContent('Deleting queue "orders"…');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();

    act(() => {
      notify.succeeded({ ...notice, pendingId: id });
    });
    expect(await screen.findByText('Deleted queue "orders"')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText('Deleting queue "orders"…')).not.toBeInTheDocument());
    expect(screen.getByRole('status')).toHaveTextContent('Deleted queue "orders"');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('announces a failure assertively with its cause and next step, and keeps it until dismissed', async () => {
    mount();
    vi.useFakeTimers({ shouldAdvanceTime: true });
    act(() => {
      notify.failed({ ...notice, cause: 'Node a2 refused the request.', next: 'Check the node and try again.' });
    });
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not delete queue "orders"');
    expect(alert).toHaveTextContent('Node a2 refused the request. Check the node and try again.');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(120_000);
    });
    expect(screen.getByRole('alert')).toBeInTheDocument();
    act(() => screen.getByRole('button').click());
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_000);
    });
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('states how far a partial outcome got, assertively and until dismissed', async () => {
    mount();
    vi.useFakeTimers({ shouldAdvanceTime: true });
    act(() => {
      notify.partial({ ...notice, reached: '2 of 3 nodes', next: 'Retry on node a3.' });
    });
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Deleted queue "orders" on 2 of 3 nodes');
    expect(alert).toHaveTextContent('Retry on node a3.');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(120_000);
    });
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });

  it('closes a success by itself', async () => {
    mount();
    vi.useFakeTimers({ shouldAdvanceTime: true });
    act(() => {
      notify.succeeded(notice);
    });
    expect(await screen.findByRole('status')).toBeInTheDocument();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000);
    });
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });
});
