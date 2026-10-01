import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';

import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';

const paged = (data: unknown[]) => ({ data, count: data.length, page: 1, pageSize: 50, hasNext: false });

describe('RouteNotFound', () => {
  it('names an unknown address and leads back to the start page', async () => {
    server.use(
      http.get('*/api/v1/auth/me', () =>
        HttpResponse.json({ id: 'u1', username: 'ops', mustChangePassword: false, grants: [] }),
      ),
      http.get('*/api/v1/clusters', () => HttpResponse.json(paged([]))),
      http.get('*/api/v1/environments', () => HttpResponse.json(paged([]))),
      http.get('*/api/v1/alerts/firing', () => HttpResponse.json(paged([]))),
    );
    renderAppAt('/no-such-page');
    expect(await screen.findByRole('heading', { level: 1, name: 'Page not found' })).toBeInTheDocument();
    expect(screen.getByText('Nothing in Studio is at /no-such-page.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Go to the start page' })).toHaveAttribute('href', '/');
  });
});
