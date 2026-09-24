import { createRoute } from '@tanstack/react-router';
import { IconNotes } from '@tabler/icons-react';
import { CONTRACT, clusterRoute, definePlugin, pluginPath, pluginView } from '@artemis-studio/plugin-sdk';

import { ID, notesKey } from './api.ts';
import { NotesList } from './NotesList.tsx';
import { NotesView } from './NotesView.tsx';

/** A page of the cluster, under the plugin's own path: /clusters/<id>/p/acme-notes. */
const notesRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: pluginPath(ID),
  component: pluginView(ID, NotesView),
});

/**
 * Everything the plugin adds to Studio's UI. Studio accepts it only when it stays in its own
 * namespace: routes under p/acme-notes, slot entries named acme-notes.…, the stream topics its
 * plugin.json declares.
 */
export default definePlugin({
  contract: CONTRACT,
  id: ID,
  routes: { cluster: [notesRoute] },
  nav: [{ group: 'messaging', order: 90, label: 'Notes', icon: IconNotes, path: pluginPath(ID), permission: `${ID}:read` }],
  slots: {
    // In every queue's detail drawer.
    'queue.detail.panels': [
      {
        id: `${ID}.queue-notes`,
        order: 90,
        title: 'Notes',
        Component: ({ clusterId, queueName }) => <NotesList clusterId={clusterId} queue={queueName} />,
      },
    ],
  },
  streamTopics: {
    // The backend publishes this topic on every change; open views refresh.
    [ID]: ({ clusterId, invalidate }) => invalidate(notesKey(clusterId)),
  },
});
