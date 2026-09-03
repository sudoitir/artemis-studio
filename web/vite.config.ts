import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The Spring Boot app serves the built SPA from classpath:/static and owns
// /api/**. In dev, Vite runs standalone on :5173 and proxies API + SSE to :8080.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/actuator': 'http://localhost:8080',
    },
  },
  build: {
    outDir: 'dist',
    // SSE streams must not be bundled/chunked oddly; nothing special needed yet.
  },
});
