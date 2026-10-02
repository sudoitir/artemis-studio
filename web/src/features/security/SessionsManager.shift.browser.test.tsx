import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { screen } from '@testing-library/react';

import { Frame, renderThemed, settle } from '../../test/browser.tsx';
import { SessionsManager } from './SessionsManager.tsx';

/**
 * The account page puts the sessions between the two-step verification and the API keys, and the sweep holds it
 * to 0.01 of layout shift. The list holds one height whatever the number of sessions, and so do the loading
 * frame and the failure that can take its place: what follows the section never moves when the answer arrives.
 */
const session = (n: number) => ({
  handle: String(n).padStart(32, 'a'),
  signedInAt: '2026-09-04T10:00:00.000Z',
  lastActivityAt: '2026-09-04T10:05:00.000Z',
  clientAddress: '203.0.113.7',
  userAgent: 'curl/8.5.0',
  current: n === 0,
});

let answer: () => Response;
beforeEach(() => {
  vi.stubGlobal('fetch', async () => {
    await new Promise((resolve) => setTimeout(resolve, 150));
    return answer();
  });
});
afterEach(() => vi.unstubAllGlobals());

const sessions = (count: number) => () =>
  Response.json({ data: Array.from({ length: count }, (_, n) => session(n)), count, page: 1, pageSize: 100 });
const failure = () =>
  Response.json({ title: 'Service Unavailable', status: 503, detail: 'Simulated.' }, { status: 503 });

async function mounted() {
  const { container } = renderThemed(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <Frame width={640} height={1200}>
        <SessionsManager />
        <p>Below the sessions</p>
      </Frame>
    </QueryClientProvider>,
    'light',
  );
  const below = screen.getByText('Below the sessions');
  await settle(() => `${below.getBoundingClientRect().top}`);
  return { container, below, loading: below.getBoundingClientRect().top };
}

describe('SessionsManager, what follows it', () => {
  it.each([
    ['one session', 1],
    ['a few sessions', 3],
    ['more sessions than the list shows', 17],
  ])('does not move when %s replace the loading frame', async (_, count) => {
    answer = sessions(count);
    const { below, loading } = await mounted();

    await screen.findAllByRole('listitem');
    await settle(() => `${below.getBoundingClientRect().top}`);

    expect(Math.abs(below.getBoundingClientRect().top - loading)).toBeLessThanOrEqual(1);
  });

  it('does not move when the failure replaces the loading frame', async () => {
    answer = failure;
    const { below, loading } = await mounted();

    await screen.findByRole('alert');
    await settle(() => `${below.getBoundingClientRect().top}`);

    expect(Math.abs(below.getBoundingClientRect().top - loading)).toBeLessThanOrEqual(1);
  });
});
