import { defineConfig } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';

// `npm run dev` proxies the API to a server started with `python -m memento_web` (MEMENTO_PORT=8080).
const backend = process.env.MEMENTO_BACKEND || 'http://127.0.0.1:8080';

export default defineConfig({
  plugins: [svelte()],
  build: { outDir: 'dist', assetsDir: 'assets', sourcemap: false },
  server: {
    // the favicon is the repository's branding/memento.svg, one level above the UI
    fs: { allow: ['..', '../../branding'] },
    proxy: Object.fromEntries(['/api', '/t', '/peaks', '/map', '/healthz'].map((p) => [p, { target: backend, changeOrigin: false }])),
  },
});
