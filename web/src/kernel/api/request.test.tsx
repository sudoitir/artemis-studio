import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';

import { server } from '../../test/setup.ts';
import { useMe } from '../auth/api.ts';
import { lifecycleQuery, request } from './request.ts';

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

/**
 * A real run says so. An absent `dryRun` leaves the choice to the server's default,
 * and a real Apply that the server read as a preview wrote nothing and said nothing.
 */
describe('lifecycleQuery()', () => {
  it('sends dryRun=false for a real run', () => {
    expect(lifecycleQuery(false)).toBe('?dryRun=false');
  });

  it('sends dryRun=true for a preview, with the override when set', () => {
    expect(lifecycleQuery(true, true)).toBe('?dryRun=true&override=true');
  });

  it('sends nothing when the caller did not choose', () => {
    expect(lifecycleQuery()).toBe('');
  });
});

/**
 * A caller's own headers add to the defaults, never replace them. Replacing them
 * dropped the CSRF header, so the XML import (which sets its content type) was
 * refused with a 403 while every JSON POST worked.
 */
describe('request() headers', () => {
  it('keeps the CSRF header when the caller sets its own content type', async () => {
    document.cookie = 'XSRF-TOKEN=tok-123';
    let seen: Headers | undefined;
    server.use(
      http.post('*/api/v1/probe', ({ request: req }) => {
        seen = req.headers;
        return HttpResponse.json({});
      }),
    );

    await request('/probe', { method: 'POST', headers: { 'content-type': 'application/xml' }, body: '<core/>' });

    expect(seen?.get('x-xsrf-token')).toBe('tok-123');
    expect(seen?.get('content-type')).toBe('application/xml');
  });
});
