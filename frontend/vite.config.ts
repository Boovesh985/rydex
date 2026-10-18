import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import type { ProxyOptions } from 'vite';
import { defineConfig } from 'vitest/config';

/**
 * Local dev: the browser talks to one origin (this server) and the proxy routes each path
 * prefix to its service, exactly as Caddy does in production (docs/adr/0013-frontend.md).
 * Order matters: the first matching prefix wins.
 */
const ride = 'http://localhost:8081';
const driver = 'http://localhost:8082';
const tracking = 'http://localhost:8083';
const payment = 'http://localhost:8084';

export const routes: [string, string][] = [
  ['/api/admin/payments', payment],
  ['/api/admin/stats', ride],
  ['/api/admin/demand', ride],
  ['/api/admin', driver],
  ['/api/payments', payment],
  ['/api/drivers', driver],
  ['/api/tracking', tracking],
  ['/api', ride], // auth, me, places, rides
];

const names: Record<string, string> = { [ride]: 'ride-service', [driver]: 'driver-service', [tracking]: 'tracking-service', [payment]: 'payment-service' };

/** A service that is not running answers 502 with a Problem Details body that names it, not a bare 500. */
function proxyTo(target: string): ProxyOptions {
  return {
    target,
    configure: (proxy) => {
      proxy.on('error', (_err, _req, res) => {
        if (!('writeHead' in res) || res.headersSent) return;
        const detail = `${names[target]} is not running (nothing answers on ${target}). Start it, then try again.`;
        res.writeHead(502, { 'Content-Type': 'application/problem+json' });
        res.end(JSON.stringify({ title: 'Bad Gateway', status: 502, detail }));
      });
    },
  };
}

function proxies() {
  return {
    ...Object.fromEntries(routes.map(([prefix, target]) => [prefix, proxyTo(target)])),
    '/ws': { target: tracking, ws: true },
  };
}

export default defineConfig({
  plugins: [react(), tailwindcss()],
  // MapLibre's tile worker is an ES module
  worker: { format: 'es' },
  // MapLibre (~1 MB, loaded with the first map) and three.js (loaded with realistic mode) are single libraries
  build: { chunkSizeWarningLimit: 1100 },
  server: { port: 5173, strictPort: true, proxy: proxies() },
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
