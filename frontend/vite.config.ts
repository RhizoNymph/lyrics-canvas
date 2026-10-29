import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8000',
        changeOrigin: true,
      },
      // Cluster alerts (nginx in production). The dev backend has no such route and answers 404,
      // which the banner treats as "feature absent" and stays quiet.
      '/_status': {
        target: 'http://localhost:8000',
        changeOrigin: true,
      },
    },
  },
});
