import createClient, { type Client, type Middleware } from 'openapi-fetch';

import type { paths } from './schema.js';

export type { components, operations, paths } from './schema.js';

/** An RFC 9457 `application/problem+json` answer. `type` is one of Studio's stable problem URIs. */
export class ProblemError extends Error {
  readonly status: number;
  readonly type: string;
  readonly title: string;
  /** The whole body: a problem may carry more members (field errors, a limit) than these. */
  readonly problem: Record<string, unknown>;

  constructor(status: number, problem: Record<string, unknown>) {
    super((problem.detail as string) ?? (problem.title as string) ?? `Request failed (${status})`);
    this.name = 'ProblemError';
    this.status = status;
    this.type = (problem.type as string) ?? 'about:blank';
    this.title = (problem.title as string) ?? 'Error';
    this.problem = problem;
  }
}

export interface StudioClientOptions {
  /** Where Studio is served, for example `https://studio.example.com`. */
  baseUrl: string;
  /** An API token; sent as `Authorization: Bearer <token>`. */
  token: string;
  /** A replacement for the global `fetch`, for a proxy or a test. */
  fetch?: (request: Request) => Promise<Response>;
}

/** Sends the token, and turns every non-2xx `problem+json` answer into a thrown {@link ProblemError}. */
const studio = (token: string): Middleware => ({
  onRequest({ request }) {
    request.headers.set('Authorization', `Bearer ${token}`);
    return request;
  },
  async onResponse({ response }) {
    if (response.ok || !response.headers.get('content-type')?.includes('application/problem+json')) return undefined;
    throw new ProblemError(response.status, (await response.json()) as Record<string, unknown>);
  },
});

/** A typed client for the Studio API: `client.GET('/api/v1/clusters')`, and so on. */
export function createStudioClient({ baseUrl, token, fetch }: StudioClientOptions): Client<paths> {
  const client = createClient<paths>({ baseUrl, fetch });
  client.use(studio(token));
  return client;
}
