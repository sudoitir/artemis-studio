import { http, HttpResponse } from 'msw';

/** Every permission the console checks, which a wildcard in a test's grants stands for. */
const UNIVERSE = [
  'address:create',
  'address:read',
  'alert:read',
  'alert:write',
  'audit:export',
  'audit:read',
  'capture:write',
  'cluster:manage',
  'cluster:read',
  'cluster:write',
  'config:apply',
  'config:read',
  'config:write',
  'connection:close',
  'connection:read',
  'data:read',
  'data:write',
  'diagnostics:bundle',
  'divert:write',
  'governance:read',
  'governance:write',
  'message:delete',
  'message:move',
  'message:read',
  'message:send',
  'metrics:read',
  'queue:create',
  'queue:delete',
  'queue:pause',
  'queue:purge',
  'queue:read',
  'queue:update',
  'rr:write',
  'settings:read',
  'settings:write',
  'team:admin',
  'token:admin',
  'user:admin',
];

type Grant = { scopeType: string; scopeId?: string | null; permissions: string[] };

const expand = (held: string[]) =>
  UNIVERSE.filter((p) => held.some((h) => h === '*' || h === p || (h.endsWith(':*') && p.startsWith(h.slice(0, -1)))));

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
  const reaching = grants.filter(
    (g) => g.scopeType === 'GLOBAL' || (clusterId !== null && g.scopeType === 'CLUSTER' && g.scopeId === clusterId),
  );
  return HttpResponse.json({
    permissions: expand(reaching.flatMap((g) => g.permissions)),
    anywhere: [],
    canSeeCluster: clusterId === null ? null : reaching.length > 0,
    teams: [],
  });
});
