import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';

import { server } from '../../test/setup.ts';
import { requestAll } from './paging.ts';

describe('requestAll', () => {
  it('follows hasNext and concatenates every page', async () => {
    const pages: string[] = [];
    server.use(
      http.get('*/api/v1/things', ({ request }) => {
        const url = new URL(request.url);
        const page = Number(url.searchParams.get('page'));
        pages.push(`${page}:${url.searchParams.get('size')}:${url.searchParams.get('kind')}`);
        return HttpResponse.json({
          data: [`row-${page}`],
          page,
          pageSize: 500,
          count: null,
          hasNext: page < 3,
        });
      }),
    );

    await expect(requestAll<string>('/things?kind=x')).resolves.toEqual(['row-1', 'row-2', 'row-3']);
    expect(pages).toEqual(['1:500:x', '2:500:x', '3:500:x']);
  });
});
