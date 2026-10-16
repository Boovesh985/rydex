import type { GeoJSONSource, Map as MlMap } from 'maplibre-gl';
import { useEffect } from 'react';
import { BUILDINGS_3D, firstSymbolLayer, worldWidth } from './theme';
import { loadRoads, TRAFFIC_COLORS, trafficAt } from './traffic';

const SOURCE = 'rx-traffic';
const LAYER = 'rx-traffic-line';
/** congestion drifts slowly: a new picture every minute is plenty */
const REFRESH_MS = 60_000;

/**
 * Google-style traffic on the main roads (green, orange, red, dark red), in 2D and on the ground
 * in 3D, under the route line, the 3D buildings and the labels.
 */
export function TrafficLayer({ map, night }: { map: MlMap; night: boolean }) {
  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setInterval> | undefined;
    // after the base map has drawn: indexing ~8,000 lines would otherwise hold up the first tiles
    // in MapLibre's (single) worker
    const ready = new Promise<void>((resolve) => (map.areTilesLoaded() ? resolve() : map.once('idle', () => resolve())));
    void Promise.all([loadRoads(), ready]).then(([net]) => {
      if (!net || cancelled || !map.getStyle() || map.getSource(SOURCE)) return;
      map.addSource(SOURCE, {
        type: 'geojson',
        data: trafficAt(net, Date.now() / 1000),
        attribution: net.attribution,
        // street-level detail is not needed for coloured road lines: fewer, simpler tiles
        maxzoom: 14,
        tolerance: 0.5,
      });
      map.addLayer(
        {
          id: LAYER,
          type: 'line',
          source: SOURCE,
          minzoom: 10,
          // the small roads only when they are not free-flowing, as Google does
          filter: ['any', ['<=', ['get', 'c'], 2], ['!=', ['get', 'b'], 'free']],
          layout: { 'line-cap': 'round', 'line-join': 'round' },
          paint: {
            'line-color': ['match', ['get', 'b'], 'free', TRAFFIC_COLORS.free, 'slow', TRAFFIC_COLORS.slow, 'heavy', TRAFFIC_COLORS.heavy, TRAFFIC_COLORS.jam],
            // a band down the middle of the road at street level, quieter so the lanes still read
            'line-width': worldWidth([[10, 1.1], [13, 2.2], [15, 3.6], [17, 6.5], [19, 11]], 1.6),
            'line-opacity': ['interpolate', ['linear'], ['zoom'], 18, 0.88, 20, 0.6],
          },
        },
        map.getLayer(BUILDINGS_3D) ? BUILDINGS_3D : firstSymbolLayer(map),
      );
      timer = setInterval(() => (map.getSource(SOURCE) as GeoJSONSource | undefined)?.setData(trafficAt(net, Date.now() / 1000)), REFRESH_MS);
    });
    return () => {
      cancelled = true;
      clearInterval(timer);
      if (!map.getStyle()) return;
      if (map.getLayer(LAYER)) map.removeLayer(LAYER);
      if (map.getSource(SOURCE)) map.removeSource(SOURCE);
    };
  }, [map]);

  useEffect(() => {
    if (map.getLayer(LAYER)) map.setPaintProperty(LAYER, 'line-opacity', night ? 0.95 : 0.88);
  }, [map, night]);

  return null;
}
