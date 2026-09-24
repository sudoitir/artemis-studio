import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { studioPlugin } from '@artemis-studio/plugin-sdk/vite';

// The id is the one in plugin.json. The preset shares React, Mantine and TanStack with Studio and
// builds into target/classes/META-INF/artemis-studio/ui, inside the plugin jar.
export default defineConfig({
  plugins: [react(), studioPlugin({ id: 'acme-notes', outDir: '../target/classes/META-INF/artemis-studio/ui' })],
});
