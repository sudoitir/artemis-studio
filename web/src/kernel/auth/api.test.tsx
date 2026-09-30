import type { ReactNode } from 'react';
import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';

import { server } from '../../test/setup.ts';
import { useLogin, useReauthenticate, useSecondFactor } from './api.ts';

const waiting = { status: 'SECOND_FACTOR_REQUIRED', me: null, methods: ['TOTP'], trustDeviceDays: 0 };

function setup() {
  const client = new QueryClient();
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  return { client, wrapper };
}

/** What the mutations of this client still hold, as text: their variables and results. */
const held = (client: QueryClient) =>
  JSON.stringify(
    client
      .getMutationCache()
      .getAll()
      .map((m) => [m.state.variables, m.state.data]),
  );

describe('sign-in mutations', () => {
  it('forget the password, the codes and the passkey answer once the screen is gone', async () => {
    server.use(
      http.post('*/api/v1/auth/login', () => HttpResponse.json(waiting)),
      http.post('*/api/v1/auth/reauthenticate', () => HttpResponse.json(waiting)),
      http.post('*/api/v1/auth/second-factor', () => HttpResponse.json(waiting)),
    );
    const { client, wrapper } = setup();
    const login = renderHook(() => useLogin(), { wrapper });
    const stepUp = renderHook(() => useReauthenticate(), { wrapper });
    const factor = renderHook(() => useSecondFactor(), { wrapper });

    await act(() => login.result.current.mutateAsync({ username: 'alice', password: 'correct-horse-battery' }));
    await act(() => stepUp.result.current.mutateAsync('correct-horse-battery'));
    await act(() => factor.result.current.mutateAsync({ recoveryCode: 'ABCDE-FGHJK' }));
    expect(held(client)).toContain('ABCDE-FGHJK');

    login.unmount();
    stepUp.unmount();
    factor.unmount();

    await waitFor(() => expect(client.getMutationCache().getAll()).toHaveLength(0));
    expect(held(client)).not.toContain('correct-horse-battery');
  });
});
