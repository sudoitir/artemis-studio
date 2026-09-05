import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';

import { server } from '../test/setup.ts';
import { useMe } from './client.ts';

/**
 * `request<T>()`'s 401 handling (identity-and-sessions spec, task 6.7) — the
 * one place a session expiry is detected, for every hook that goes through
 * it, not just `useMe`.
 */
describe('request() 401 handling', () => {
  const originalLocation = window.location;

  beforeEach(() => {
    // jsdom's `location.assign` throws "not implemented" — stub the whole
    // object so the redirect can actually be observed.
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: { ...originalLocation, pathname: '/', assign: vi.fn() },
    });
  });

  afterEach(() => {
    Object.defineProperty(window, 'location', { configurable: true, value: originalLocation });
  });

  function wrapper({ children }: { children: ReactNode }) {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
    return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
  }

  it('redirects to /login on a 401 response', async () => {
    server.use(http.get('*/api/v1/auth/me', () => HttpResponse.json({}, { status: 401 })));

    const { result } = renderHook(() => useMe(), { wrapper });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(window.location.assign).toHaveBeenCalledWith('/login');
  });

  it('does not redirect again when already on /login', async () => {
    window.location.pathname = '/login';
    server.use(http.get('*/api/v1/auth/me', () => HttpResponse.json({}, { status: 401 })));

    const { result } = renderHook(() => useMe(), { wrapper });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(window.location.assign).not.toHaveBeenCalled();
  });
});
