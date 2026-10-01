import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';

import { paged } from '../../kernel/api/paging.ts';
import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { ClusterHome } from './ClusterHome.tsx';

describe('ClusterHome', () => {
  it('holds a frame for the form while the clusters load, under no heading of its own', () => {
    server.use(http.get('*/api/v1/clusters', () => new Promise(() => undefined)));
    renderWithProviders(<ClusterHome />);

    expect(screen.queryByRole('heading', { level: 1 })).toBeNull();
    expect(screen.getByRole('status')).toHaveTextContent('Loading clusters');
  });

  it('teaches what a cluster is and offers the registration form, without a page title of its own', async () => {
    server.use(http.get('*/api/v1/clusters', () => HttpResponse.json(paged([]))));
    renderWithProviders(<ClusterHome />);

    expect(await screen.findByText('No clusters yet')).toBeInTheDocument();
    expect(screen.getByText(/A cluster is a set of Artemis brokers/)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Register a cluster', level: 2 })).toBeInTheDocument();
    expect(screen.getByLabelText(/Broker management URLs/)).toBeInTheDocument();
    expect(screen.queryByRole('heading', { level: 1 })).toBeNull();
  });

  it('says why the clusters could not be read, instead of waiting for ever', async () => {
    server.use(
      http.get('*/api/v1/clusters', () =>
        HttpResponse.json({ title: 'Error', detail: 'The cluster store is down.' }, { status: 500 }),
      ),
    );
    renderWithProviders(<ClusterHome />);

    expect(await screen.findByText('The cluster store is down.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });
});
