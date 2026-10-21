/**
 * The one API origin's routing table (ADR 0013): the first path prefix that matches picks the service.
 * The Vite proxy (vite.config.ts) routes by it locally and Caddy (infra/caddy/Caddyfile) in containers;
 * edge.test.ts keeps Caddy's copy in step. /ws goes to tracking-service in both.
 */
export const servicePorts = {
  'ride-service': 8081,
  'driver-service': 8082,
  'tracking-service': 8083,
  'payment-service': 8084,
} as const;

export type Service = keyof typeof servicePorts;

export const apiRoutes: [string, Service][] = [
  ['/api/admin/payments', 'payment-service'],
  ['/api/admin/stats', 'ride-service'],
  ['/api/admin/demand', 'ride-service'],
  ['/api/admin', 'driver-service'],
  ['/api/payments', 'payment-service'],
  ['/api/drivers', 'driver-service'],
  ['/api/tracking', 'tracking-service'],
  ['/api', 'ride-service'], // auth, me, places, rides
];
