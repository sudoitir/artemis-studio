import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, screen, waitFor } from '@testing-library/react';
import { Notifications, notifications } from '@mantine/notifications';

import { renderWithProviders } from '../test/render.tsx';
import { setInAppNavigate } from './inAppNavigation.ts';
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

  it('dismisses a toast that stays until dismissed, by its id', async () => {
    mount();
    let id = '';
    act(() => {
      id = notify.failed({ ...notice, cause: 'Node a2 refused the request.', next: 'Try again.' });
    });
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not delete queue "orders"');

    act(() => notify.dismiss(id));
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
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

  describe('a held operation', () => {
    /** The shape of the kernel's `OperationHeldError`, which this part reads without importing it. */
    const held = Object.assign(new Error('Sent for approval: Purge queue "orders"'), {
      name: 'OperationHeldError',
      heldOperation: { id: 'h-1', summary: 'Purge queue "orders"', expiresAt: '', link: '/api/v1/held-operations/h-1' },
    });
    const failure = { ...notice, cause: 'The broker refused.', next: 'Try again.' };

    it('says it was sent for approval, politely, with a link to the request', async () => {
      mount();
      act(() => {
        notify.held({ id: 'h-1', summary: 'Purge queue "orders"' });
      });
      const status = await screen.findByRole('status');
      expect(status).toHaveTextContent('Sent for approval');
      expect(status).toHaveTextContent('Purge queue "orders" waits for a second person.');
      expect(screen.getByRole('link', { name: 'View request' })).toHaveAttribute('href', '/approvals/h-1');
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });

    it('follows the link inside the app on a plain click', async () => {
      const navigate = vi.fn();
      const unregister = setInAppNavigate(navigate);
      mount();
      act(() => {
        notify.held({ id: 'h-1', summary: 'Purge queue "orders"' });
      });
      act(() => (screen.getByRole('link', { name: 'View request' }) as HTMLAnchorElement).click());
      expect(navigate).toHaveBeenCalledWith('/approvals/h-1');
      unregister();
    });

    it('settles a held error as held, replacing the pending toast, never as a failure', async () => {
      mount();
      let pendingId = '';
      act(() => {
        pendingId = notify.pending(notice);
      });
      act(() => {
        notify.settle(held, { ...failure, pendingId });
      });
      expect(await screen.findByText('Sent for approval')).toBeInTheDocument();
      await waitFor(() => expect(screen.queryByText('Deleting queue "orders"…')).not.toBeInTheDocument());
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
      expect(screen.queryByText(/Could not delete/)).not.toBeInTheDocument();
    });

    it('settles any other error as the failure it was given', async () => {
      mount();
      act(() => {
        notify.settle(new Error('nope'), failure);
      });
      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent('Could not delete queue "orders"');
      expect(alert).toHaveTextContent('The broker refused. Try again.');
    });

    it("settles a gate refusal in the gate's own words", async () => {
      mount();
      const denied = {
        status: 403,
        type: 'https://artemis-studio.dev/problems/operation-denied',
        problem: { detail: 'Purges need a change window.' },
      };
      act(() => {
        notify.settle(denied, failure);
      });
      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent('Could not delete queue "orders"');
      expect(alert).toHaveTextContent('Purges need a change window.');
      expect(alert).toHaveTextContent('Nothing was changed.');
    });
  });
});
