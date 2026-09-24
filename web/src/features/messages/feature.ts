import { IconAlertTriangle } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute, featureView } from '../../kernel/routing/roots.ts';
import { DlqView } from './DlqView.tsx';
import { MessagesView } from './MessagesView.tsx';
import {
  BrowseQueueMessages,
  CopyMessage,
  DeleteMessage,
  MoveMessage,
  OpenMessage,
  RetryMessage,
} from './rowActions.tsx';

/** Message-browse navigable state (ADR-0021). Selection stays ephemeral (D10), not in the URL. */
export interface MessagesSearch {
  node?: string;
  filter?: string;
  page?: number;
  /** The message whose detail is open — so a link to one message opens it. */
  message?: string;
}

function validateMessagesSearch(raw: Record<string, unknown>): MessagesSearch {
  const out: MessagesSearch = {};
  if (typeof raw.node === 'string' && raw.node) out.node = raw.node;
  if (typeof raw.filter === 'string' && raw.filter) out.filter = raw.filter;
  if ((typeof raw.message === 'string' || typeof raw.message === 'number') && /^\d+$/.test(String(raw.message))) {
    out.message = String(raw.message);
  }
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
    'message.actions': [
      { id: 'messages.open', order: 10, section: 'open', Component: OpenMessage },
      { id: 'messages.copy', order: 10, section: 'copy', Component: CopyMessage },
      { id: 'messages.move', order: 10, section: 'operate', Component: MoveMessage },
      { id: 'messages.retry', order: 20, section: 'operate', Component: RetryMessage },
      { id: 'messages.delete', order: 10, section: 'destroy', Component: DeleteMessage },
    ],
  },
});
