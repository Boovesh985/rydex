import { describe, expect, it } from 'vitest';
import caddyfile from '../../infra/caddy/Caddyfile?raw';
import { apiRoutes, servicePorts } from './edge';

/** Caddy's `handle <prefix>* { import proxy <service>:<port> }` blocks, in file order. */
function caddyRoutes(): [string, string, number][] {
  const found = caddyfile.matchAll(/handle (\/\S+)\* \{\s*(?:import proxy|reverse_proxy) ([\w-]+):(\d+)/g);
  return [...found].map((m) => [m[1], m[2], Number(m[3])]);
}

describe('Caddy routes like the dev proxy', () => {
  it('sends every API prefix to the same service, most specific first', () => {
    const api = caddyRoutes().filter(([prefix]) => prefix.startsWith('/api'));
    expect(api).toEqual(apiRoutes.map(([prefix, service]) => [prefix, service, servicePorts[service]]));
  });

  it('sends the WebSocket endpoint to tracking-service', () => {
    expect(caddyRoutes()).toContainEqual(['/ws', 'tracking-service', 8083]);
  });

  it('never routes internal endpoints', () => {
    for (const internal of ['/actuator', '/swagger-ui', '/v3/api-docs', '/.well-known', '/dev', 'ai-service']) {
      expect(caddyfile).not.toContain(`handle ${internal}`);
      expect(caddyfile).not.toMatch(new RegExp(`reverse_proxy ${internal}|import proxy ${internal}`));
    }
  });
});
