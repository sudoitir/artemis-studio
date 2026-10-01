import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { screen } from '@testing-library/react';

import { Frame, renderThemed, settle } from '../../test/browser.tsx';
import { TwoStepSection } from './TwoStepSection.tsx';

/**
 * The account page shows the two-step verification above the sessions and the API keys, and the sweep holds it
 * to 0.01 of layout shift. The loading frame and the failure hold the height of the section for an account with
 * an authenticator app and recovery codes (the common one), so what follows moves by nothing.
 */
const STATUS = {
  passwordAccount: true,
  required: true,
  enrolled: true,
  totpEnrolled: true,
  recoveryCodesRemaining: 10,
  webauthn: {
    available: false,
    reason: 'Set ARTEMIS_STUDIO_PUBLIC_URL to the address people open Studio at to enable passkeys.',
  },
  passkeys: [],
  trustedDevices: [],
};

let answer: () => Response;
beforeEach(() => {
  vi.stubGlobal('fetch', async () => {
    await new Promise((resolve) => setTimeout(resolve, 150));
    return answer();
  });
});
afterEach(() => vi.unstubAllGlobals());

async function mounted() {
  const { container } = renderThemed(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <Frame width={640} height={1200}>
        <TwoStepSection />
        <p>Below the section</p>
      </Frame>
    </QueryClientProvider>,
    'light',
  );
  const below = screen.getByText('Below the section');
  await settle(() => `${below.getBoundingClientRect().top}`);
  return { container, below, loading: below.getBoundingClientRect().top };
}

describe('TwoStepSection, what follows it', () => {
  it('does not move when the status replaces the loading frame', async () => {
    answer = () => Response.json(STATUS);
    const { below, loading } = await mounted();

    await screen.findByText('Authenticator app');
    await settle(() => `${below.getBoundingClientRect().top}`);

    expect(Math.abs(below.getBoundingClientRect().top - loading)).toBeLessThanOrEqual(4);
  });

  it('does not move when the failure replaces the loading frame', async () => {
    answer = () => Response.json({ title: 'Service Unavailable', status: 503, detail: 'Simulated.' }, { status: 503 });
    const { below, loading } = await mounted();

    await screen.findByRole('alert');
    await settle(() => `${below.getBoundingClientRect().top}`);

    expect(Math.abs(below.getBoundingClientRect().top - loading)).toBeLessThanOrEqual(1);
  });
});
