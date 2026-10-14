# @rahdar/plugin-sdk

Build the UI of an [Rahdar](https://github.com/sudoitir/rahdar) plugin.

- **Typings** for everything a plugin's UI may use from Rahdar: routes under Rahdar's own roots, slots,
  navigation, the API client, permission checks and shared components. At runtime Rahdar provides
  the SDK itself, so a plugin's bundle never contains a copy.
- **A Vite preset** that builds the UI as a Module Federation remote taking React, Mantine and
  TanStack from Rahdar.

```ts
// vite.config.ts
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { rahdarPlugin } from '@rahdar/plugin-sdk/vite';

export default defineConfig({ plugins: [react(), rahdarPlugin({ id: 'acme-notes' })] });
```

```tsx
// src/feature.tsx
import { createRoute } from '@tanstack/react-router';
import { CONTRACT, clusterRoute, definePlugin, pluginPath, pluginView } from '@rahdar/plugin-sdk';

const notes = createRoute({
  getParentRoute: () => clusterRoute,
  path: pluginPath('acme-notes', 'notes'),
  component: pluginView('acme-notes', NotesView),
});

export default definePlugin({ contract: CONTRACT, id: 'acme-notes', routes: { cluster: [notes] } });
```

The SDK's version is the Rahdar version it was built from, and its peer dependencies are the exact
React, Mantine and TanStack versions that Rahdar ships. Build against the SDK of the oldest Rahdar
your plugin supports (`rahdar.since` in `plugin.json`).

The guide: <https://rahdar.dev/guide/plugins>.
