import { IconAlertTriangle } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute, featureView } from '../../kernel/routing/roots.ts';
import { DlqView } from './DlqView.tsx';
import { MessagesView } from './MessagesView.tsx';
import { BrowseQueueMessages } from './rowActions.tsx';

/** Message-browse navigable state (ADR-0021). Selection stays ephemeral (D10), not in the URL. */
export interface MessagesSearch {
  node?: string;
  filter?: string;
  page?: number;
}

function validateMessagesSearch(raw: Record<string, unknown>): MessagesSearch {
  const out: MessagesSearch = {};
  if (typeof raw.node === 'string' && raw.node) out.node = raw.node;
  if (typeof raw.filter === 'string' && raw.filter) out.filter = raw.filter;
  const page = Number(raw.page);
  if (Number.isFinite(page) && page > 1) out.page = Math.floor(page);
  return out;
}

const messagesRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'queues/$queueName/messages',
  component: featureView('messages', MessagesView),
  validateSearch: validateMessagesSearch,
});

const dlqRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'dlq',
  component: featureView('messages', DlqView),
});

/** Browsing, sending, moving and deleting messages, and the dead-letter view. */
export const messagesFeature = defineFeature({
  contract: CONTRACT,
  id: 'messages',
  routes: { cluster: [messagesRoute, dlqRoute] },
  nav: [
    { group: 'messaging', order: 20, label: 'DLQ', icon: IconAlertTriangle, path: 'dlq', permission: 'message:read' },
  ],
  slots: {
    'queue.actions': [{ id: 'messages.browse', order: 20, section: 'open', Component: BrowseQueueMessages }],
  },
});
