import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { rahdarPlugin } from '@rahdar/plugin-sdk/vite';

// The id is the one in plugin.json. The preset shares React, Mantine and TanStack with Rahdar and
// builds into target/classes/META-INF/rahdar/ui, inside the plugin jar.
export default defineConfig({
  plugins: [react(), rahdarPlugin({ id: 'acme-notes', outDir: '../target/classes/META-INF/rahdar/ui' })],
});
