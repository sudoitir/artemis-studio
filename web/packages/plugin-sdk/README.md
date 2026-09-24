# @artemis-studio/plugin-sdk

Build the UI of an [Artemis Studio](https://github.com/sudoitir/artemis-studio) plugin.

- **Typings** for everything a plugin's UI may use from Studio: routes under Studio's own roots, slots,
  navigation, the API client, permission checks and shared components. At runtime Studio provides
  the SDK itself, so a plugin's bundle never contains a copy.
- **A Vite preset** that builds the UI as a Module Federation remote taking React, Mantine and
  TanStack from Studio.

```ts
// vite.config.ts
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { studioPlugin } from '@artemis-studio/plugin-sdk/vite';

export default defineConfig({ plugins: [react(), studioPlugin({ id: 'acme-notes' })] });
```

```tsx
// src/feature.tsx
import { createRoute } from '@tanstack/react-router';
import { CONTRACT, clusterRoute, definePlugin, pluginPath, pluginView } from '@artemis-studio/plugin-sdk';

const notes = createRoute({
  getParentRoute: () => clusterRoute,
  path: pluginPath('acme-notes', 'notes'),
  component: pluginView('acme-notes', NotesView),
});

export default definePlugin({ contract: CONTRACT, id: 'acme-notes', routes: { cluster: [notes] } });
```

The SDK's version is the Studio version it was built from, and its peer dependencies are the exact
React, Mantine and TanStack versions that Studio ships. Build against the SDK of the oldest Studio
your plugin supports (`studio.since` in `plugin.json`).

The guide: <https://sudoitir.github.io/artemis-studio/guide/plugins>.
