import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url';

/**
 * Fetches the map's code ahead of need: the map components with MapLibre (~300 KB compressed) and
 * MapLibre's tile worker (~500 KB, fetched by a map when it starts). Importing this file pulls in
 * neither; the browser keeps both for when the first map opens.
 */
export function prefetchMap() {
  void import('./MapView').catch(() => undefined);
  void fetch(workerUrl, { priority: 'low' } as RequestInit).catch(() => undefined);
}
