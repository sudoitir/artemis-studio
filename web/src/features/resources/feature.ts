import { createElement } from 'react';
import { IconAt, IconNetwork, IconPlugConnected, IconSend, IconUsers } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute, featureView } from '../../kernel/routing/roots.ts';
import { validateResourceSearch } from '../../kernel/routing/search.ts';
import { keys } from './api.ts';
import { ResourceView } from './ResourceView.tsx';
import {
  AddressCloseConsumers,
  AddressCopy,
  AddressLink,
  AddressOpenConsumers,
  ConnectionClose,
  ConnectionCopy,
  ConnectionLink,
  ConnectionOpenSessions,
  ConsumerClose,
  ConsumerCopy,
  ConsumerOpenSession,
  ProducerCopy,
  ProducerOpenSession,
  SessionClose,
  SessionCopy,
  SessionLink,
  SessionOpenRelated,
} from './rowActions.tsx';

const KINDS = [
  { kind: 'addresses', label: 'Addresses', icon: IconAt },
  { kind: 'consumers', label: 'Consumers', icon: IconUsers },
  { kind: 'sessions', label: 'Sessions', icon: IconPlugConnected },
  { kind: 'connections', label: 'Connections', icon: IconNetwork },
  { kind: 'producers', label: 'Producers', icon: IconSend },
] as const;

const resourceRoutes = KINDS.map(({ kind }) =>
  createRoute({
    getParentRoute: () => clusterRoute,
    path: kind,
    component: featureView('resources', () => createElement(ResourceView, { kind })),
    validateSearch: validateResourceSearch,
  }),
);

/** Live cross-node views of addresses, consumers, sessions, connections and producers, and closing connections. */
export const resourcesFeature = defineFeature({
  contract: CONTRACT,
  id: 'resources',
  routes: { cluster: resourceRoutes },
  nav: KINDS.map(({ kind, label, icon }, i) => ({
    group: 'resources' as const,
    order: (i + 1) * 10,
    label,
    icon,
    path: kind,
    permission: 'cluster:read',
  })),
  slots: {
    'connection.actions': [
      { id: 'resources.connection.open', order: 20, section: 'open', Component: ConnectionOpenSessions },
      { id: 'resources.connection.copy', order: 10, section: 'copy', Component: ConnectionCopy },
      { id: 'resources.connection.close', order: 10, section: 'operate', Component: ConnectionClose },
    ],
    'session.actions': [
      { id: 'resources.session.open', order: 20, section: 'open', Component: SessionOpenRelated },
      { id: 'resources.session.copy', order: 10, section: 'copy', Component: SessionCopy },
      { id: 'resources.session.close', order: 10, section: 'operate', Component: SessionClose },
    ],
    'consumer.actions': [
      { id: 'resources.consumer.open', order: 20, section: 'open', Component: ConsumerOpenSession },
      { id: 'resources.consumer.copy', order: 10, section: 'copy', Component: ConsumerCopy },
      { id: 'resources.consumer.close', order: 10, section: 'operate', Component: ConsumerClose },
    ],
    'producer.actions': [
      { id: 'resources.producer.open', order: 20, section: 'open', Component: ProducerOpenSession },
      { id: 'resources.producer.copy', order: 10, section: 'copy', Component: ProducerCopy },
    ],
    'address.actions': [
      { id: 'resources.address.open', order: 20, section: 'open', Component: AddressOpenConsumers },
      { id: 'resources.address.copy', order: 10, section: 'copy', Component: AddressCopy },
      { id: 'resources.address.close', order: 10, section: 'operate', Component: AddressCloseConsumers },
    ],
    'connection.link': [{ id: 'resources.connection.link', order: 10, Component: ConnectionLink }],
    'session.link': [{ id: 'resources.session.link', order: 10, Component: SessionLink }],
    'address.link': [{ id: 'resources.address.link', order: 10, Component: AddressLink }],
  },
  streamTopics: {
    consumers: ({ clusterId, invalidate }) => invalidate(keys.topic(clusterId, 'consumers')),
    sessions: ({ clusterId, invalidate }) => invalidate(keys.topic(clusterId, 'sessions')),
    connections: ({ clusterId, invalidate }) => invalidate(keys.topic(clusterId, 'connections')),
  },
});
