import { describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../test/render.tsx';
import { ErrorState } from './ErrorState.tsx';

/** The shape of an `ApiError`, which this part reads without importing it. */
function apiError(status: number, problem: Record<string, unknown> = {}, extra: Record<string, unknown> = {}) {
  return { status, problem, ...extra };
}

describe('ErrorState', () => {
  it('is an alert', () => {
    renderWithProviders(<ErrorState error={apiError(404)} />);
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });

  it('says Studio could not be reached for a network failure, and offers retry', async () => {
    const onRetry = vi.fn();
    renderWithProviders(<ErrorState error={new TypeError('Failed to fetch')} onRetry={onRetry} />);
    expect(screen.getByText('Studio could not be reached')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it('treats status 0 as the network', () => {
    renderWithProviders(<ErrorState error={apiError(0)} />);
    expect(screen.getByText('Studio could not be reached')).toBeInTheDocument();
  });

  it('asks the operator to sign in again on 401', () => {
    renderWithProviders(<ErrorState error={apiError(401)} onRetry={() => undefined} />);
    expect(screen.getByText('You are signed out')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Sign in again' })).toHaveAttribute('href', '/login');
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
  });

  it('names the missing permission on 403 when the problem says it', () => {
    renderWithProviders(<ErrorState error={apiError(403, { permission: 'queues:delete' })} />);
    expect(screen.getByText('Your role does not include the queues:delete permission.')).toBeInTheDocument();
    expect(screen.getByText('Ask an administrator to grant queues:delete.')).toBeInTheDocument();
  });

  it('still explains a 403 that names no permission', () => {
    renderWithProviders(<ErrorState error={apiError(403)} />);
    expect(screen.getByText('Your role does not allow it.')).toBeInTheDocument();
  });

  it('says it was not found on 404, without retry', () => {
    renderWithProviders(<ErrorState error={apiError(404)} onRetry={() => undefined} />);
    expect(screen.getByText('Not found')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
  });

  it('shows the server detail for a 409 and offers a reload', () => {
    renderWithProviders(
      <ErrorState error={apiError(409, { detail: 'The queue still has consumers.' })} onRetry={() => undefined} />,
    );
    expect(screen.getByText('This conflicts with the current state')).toBeInTheDocument();
    expect(screen.getByText('The queue still has consumers.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('lists the invalid fields on 422', () => {
    renderWithProviders(
      <ErrorState
        error={apiError(
          422,
          {},
          {
            fieldErrors: [
              { field: 'name', message: 'must not be blank' },
              { field: 'port', message: 'must be at most 65535' },
            ],
          },
        )}
      />,
    );
    const items = within(screen.getByRole('list')).getAllByRole('listitem');
    expect(items.map((item) => item.textContent)).toEqual(['name: must not be blank', 'port: must be at most 65535']);
  });

  it('gives the wait on 429', () => {
    renderWithProviders(<ErrorState error={apiError(429, { retryAfter: 30 })} onRetry={() => undefined} />);
    expect(screen.getByText('Too many requests')).toBeInTheDocument();
    expect(screen.getByText('Wait 30 seconds, then retry.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('says to wait a moment on 429 with no retry-after', () => {
    renderWithProviders(<ErrorState error={apiError(429)} />);
    expect(screen.getByText('Wait a moment, then retry.')).toBeInTheDocument();
  });

  it('quotes the request id on a server error', () => {
    renderWithProviders(<ErrorState error={apiError(500, { requestId: 'req-42' })} onRetry={() => undefined} />);
    expect(screen.getByText('Studio failed to complete the request')).toBeInTheDocument();
    expect(screen.getByText('Request id: req-42')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it.each([
    ['UNREACHABLE', 'The broker is unreachable', 502, true],
    ['UNAUTHORIZED', 'The broker rejected the credentials', 422, false],
    ['NOT_ARTEMIS', 'There is no Artemis broker at this agent', 422, false],
    ['WRONG_PATH', 'There is no Jolokia agent at this address', 422, false],
    ['TLS_FAILED', 'The TLS handshake with the broker failed', 502, false],
    ['BAD_RESPONSE', 'The broker sent an unexpected response', 502, true],
    ['UNSUPPORTED_VERSION', 'This Artemis version is not supported', 422, false],
  ])('reads the broker error kind %s', (kind, title, status, retryable) => {
    renderWithProviders(
      <ErrorState error={apiError(status, {}, { brokerErrorKind: kind })} onRetry={() => undefined} />,
    );
    expect(screen.getByText(title)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Retry' }) !== null).toBe(retryable);
  });

  it('shows the problem title and detail for any other refusal', () => {
    renderWithProviders(
      <ErrorState error={apiError(400, { detail: 'Bad paging cursor.' }, { title: 'Bad request' })} />,
    );
    expect(screen.getByText('Bad request')).toBeInTheDocument();
    expect(screen.getByText('Bad paging cursor.')).toBeInTheDocument();
  });

  it('offers no retry without a handler', () => {
    renderWithProviders(<ErrorState error={apiError(500)} />);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('has an inline variant', () => {
    renderWithProviders(<ErrorState error={apiError(404)} variant="inline" />);
    expect(screen.getByRole('alert')).toHaveAttribute('data-variant', 'inline');
  });
});
