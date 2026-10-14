import { describe, expect, it } from 'vitest';

import { createRahdarClient, ProblemError } from './index.ts';

const seen: Request[] = [];
const reply = (response: () => Response) => async (request: Request) => {
  seen.push(request);
  return response();
};

describe('createRahdarClient', () => {
  it('sends the token as a bearer credential', async () => {
    const client = createRahdarClient({
      baseUrl: 'http://rahdar.test',
      token: 'secret',
      fetch: reply(() => Response.json({ data: [], page: 1, pageSize: 50, count: 0, hasNext: false })),
    });
    await client.GET('/api/v1/clusters');
    expect(seen.at(-1)?.headers.get('Authorization')).toBe('Bearer secret');
  });

  it('throws a ProblemError carrying the problem members', async () => {
    const client = createRahdarClient({
      baseUrl: 'http://rahdar.test',
      token: 'secret',
      fetch: reply(
        () =>
          new Response(
            JSON.stringify({
              type: 'https://rahdar.dev/problems/forbidden',
              title: 'Forbidden',
              detail: 'No.',
            }),
            {
              status: 403,
              headers: { 'content-type': 'application/problem+json' },
            },
          ),
      ),
    });
    const error = await client.GET('/api/v1/clusters').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ProblemError);
    expect(error).toMatchObject({ status: 403, type: 'https://rahdar.dev/problems/forbidden', message: 'No.' });
  });
});
