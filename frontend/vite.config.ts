import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
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

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      ...Object.fromEntries(routes.map(([prefix, target]) => [prefix, { target }])),
      '/ws': { target: tracking, ws: true },
    },
  },
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
