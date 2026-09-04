import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';

import { renderWithProviders } from '../test/render.tsx';
import { server } from '../test/setup.ts';
import { MessageDetailPanel } from './MessageDetailPanel.tsx';

function detail(over: Record<string, unknown> = {}) {
  return {
    messageId: 146,
    type: 3,
    durable: true,
    priority: 0,
    timestamp: 1788501365987,
    expiration: 0,
    size: 8184,
    groupId: null,
    correlationId: null,
    userId: null,
    body: 'PPPPP',
    bodyEncoding: 'TEXT',
    contentType: null,
    bodyTruncated: false,
    observedLimitBytes: null,
    transport: 'JOLOKIA',
    node: '11111111-1111-1111-1111-111111111111',
    stringProperties: { orderId: 'BIG-1' },
    intProperties: {},
    longProperties: {},
    doubleProperties: {},
    booleanProperties: {},
    ...over,
  };
}

function mockDetail(body: Record<string, unknown>) {
  server.use(
    http.get('*/api/v1/clusters/:c/queues/:q/messages/:id', () => HttpResponse.json(body)),
  );
}

describe('MessageDetailPanel', () => {
  const base = {
    clusterId: 'c1',
    queueName: 'PHASE3.SRC',
    onClose: () => {},
  };

  it('shows the truncation banner with the broker.xml snippet when bodyTruncated', async () => {
    mockDetail(detail({ bodyTruncated: true, observedLimitBytes: 256 }));
    renderWithProviders(<MessageDetailPanel {...base} messageId="146" />);

    expect(await screen.findByText('This message is truncated')).toBeInTheDocument();
    expect(screen.getAllByText(/management-message-attribute-size-limit/).length).toBeGreaterThan(0);
    expect(screen.getByText(/connect the Core client/)).toBeInTheDocument();
  });

  it('shows the transport badge and a binary body notice when read over Core', async () => {
    mockDetail(detail({ transport: 'CORE', bodyEncoding: 'BASE64', body: 'AQIDBA==' }));
    renderWithProviders(<MessageDetailPanel {...base} messageId="146" />);

    expect(await screen.findByText('via Core')).toBeInTheDocument();
    expect(screen.getByText(/binary · base64/)).toBeInTheDocument();
  });

  it('does not show the truncation banner for a whole message', async () => {
    mockDetail(detail({ bodyTruncated: false }));
    renderWithProviders(<MessageDetailPanel {...base} messageId="146" />);

    expect(await screen.findByText('String properties')).toBeInTheDocument();
    expect(screen.queryByText('This message is truncated')).not.toBeInTheDocument();
  });
});
