/** Build-time settings (Vite env). Empty API URL = same origin (the dev proxy, or a reverse proxy). */
const apiUrl = (import.meta.env.VITE_API_URL ?? '').replace(/\/+$/, '');

function defaultWsUrl(): string {
  const base = apiUrl || window.location.origin;
  return base.replace(/^http/, 'ws') + '/ws';
}

export const config = {
  apiUrl,
  wsUrl: (import.meta.env.VITE_WS_URL as string | undefined) || defaultWsUrl(),
  /** The "Simulate driving" switch: always in dev builds, opt-in for deployed demos. */
  simulator: import.meta.env.DEV || import.meta.env.VITE_ENABLE_SIMULATOR === 'true',
  /** Default map centre (Chennai Central). */
  center: { lat: 13.0827, lng: 80.2707 },
};
