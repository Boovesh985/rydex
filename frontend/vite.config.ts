import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import type { ProxyOptions } from 'vite';
import { defineConfig } from 'vitest/config';
import { apiRoutes, servicePorts, type Service } from './src/edge.ts';

/**
 * Local dev: the browser talks to one origin (this server) and the proxy routes each path
 * prefix to its service (src/edge.ts), exactly as Caddy does in containers (docs/adr/0013-frontend.md).
 * Order matters: the first matching prefix wins.
 */
const local = (service: Service) => `http://localhost:${servicePorts[service]}`;
const tracking = local('tracking-service');

/** A service that is not running answers 502 with a Problem Details body that names it, not a bare 500. */
function proxyTo(service: Service): ProxyOptions {
  const target = local(service);
  return {
    target,
    configure: (proxy) => {
      proxy.on('error', (_err, _req, res) => {
        if (!('writeHead' in res) || res.headersSent) return;
        const detail = `${service} is not running (nothing answers on ${target}). Start it, then try again.`;
        res.writeHead(502, { 'Content-Type': 'application/problem+json' });
        res.end(JSON.stringify({ title: 'Bad Gateway', status: 502, detail }));
      });
    },
  };
}

function proxies() {
  return {
    ...Object.fromEntries(apiRoutes.map(([prefix, service]) => [prefix, proxyTo(service)])),
    '/ws': { target: tracking, ws: true },
  };
}

export default defineConfig({
  plugins: [react(), tailwindcss()],
  // MapLibre's tile worker is an ES module
  worker: { format: 'es' },
  // MapLibre (~1 MB, loaded with the first map) and three.js (loaded with realistic mode) are single libraries
  build: { chunkSizeWarningLimit: 1100 },
  server: {
    port: 5173,
    strictPort: true,
    proxy: proxies(),
    // the project, and Caddy's routes outside it (read by src/edge.test.ts)
    fs: { allow: ['.', '../infra/caddy'] },
  },
  // `vite preview` serves the production build against the same services (bundle checks)
  preview: { port: 4173, strictPort: true, proxy: proxies() },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    css: false,
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/**/*.test.{ts,tsx}', 'src/test/**', 'src/main.tsx', 'src/vite-env.d.ts'],
    },
  },
});
