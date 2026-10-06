import { http, HttpResponse } from 'msw';

import { accessFor, type Grant } from './accessSummary.ts';

/**
 * `/me/access` for a test that only mocks `/auth/me`: the same answer the server gives for role grants that
 * name no environment, worked out from the grants that mock returns. A test about environments or teams mocks
 * `/me/access` itself.
 */
export const accessHandler = http.get('*/api/v1/me/access', async ({ request }) => {
  const clusterId = new URL(request.url).searchParams.get('clusterId');
  let grants: Grant[] = [];
  try {
    const me = (await (await fetch(new URL('/api/v1/auth/me', request.url))).json()) as { grants?: Grant[] };
    grants = me.grants ?? [];
  } catch {
    // Nobody is signed in in this test.
  }
  return HttpResponse.json(accessFor(grants, clusterId));
});
