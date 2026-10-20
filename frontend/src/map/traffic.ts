import type { FeatureCollection, LineString } from 'geojson';

/**
 * Simulated city traffic (ADR 0016). The same formula runs in the simulator
 * (simulator/rydex_sim/traffic.py), so the red stretches on the map are where its cars crawl, and in
 * ai-service (app/traffic.py), which times the suggested route through them.
 *
 * Each segment of /data/traffic-roads.json (Chennai's main roads from OpenStreetMap, cut into
 * ~450 m pieces) has a road class and a stable hash in [0, 1). Congestion follows Chennai's day
 * (morning and evening rush, a quiet night), is heavier on the big roads and on "busy" segments,
 * and drifts so the picture changes over half an hour.
 */

/** motorway/trunk, primary, secondary, tertiary */
const CLASS_WEIGHT = [1.15, 1.0, 0.85, 0.7];
const IST_OFFSET_H = 5.5;

/** Levels: below FREE free flow, below SLOW slow, below JAM heavy, else jammed. */
export const FREE = 0.4;
export const SLOW = 0.62;
export const JAM = 0.82;

/** Hour of the day in India (0-24) at epoch seconds t. */
export function istHour(t: number): number {
  return (((t / 3600 + IST_OFFSET_H) % 24) + 24) % 24;
}

function bump(h: number, center: number, width: number): number {
  let d = Math.abs(h - center);
  d = Math.min(d, 24 - d);
  return Math.exp(-((d / width) ** 2));
}

/** How busy the city is at this hour: about 0.03 at 3 am, over 0.8 in the evening rush. */
export function demand(hour: number): number {
  return 0.15 + 0.6 * bump(hour, 9.25, 1.6) + 0.7 * bump(hour, 18.75, 2.6) + 0.25 * bump(hour, 13.5, 3.0) - 0.12 * bump(hour, 3.0, 2.5);
}

/** Congestion of a segment at epoch seconds t: 0 free flow .. 1 standstill. */
export function level(segHash: number, roadClass: number, t: number): number {
  const wave = Math.sin(2 * Math.PI * (t / 1800 + segHash * 7.13)) * Math.cos(2 * Math.PI * (t / 4700 + segHash * 3.71));
  const base = demand(istHour(t)) * CLASS_WEIGHT[roadClass] * (0.55 + 0.9 * segHash);
  return Math.min(1, Math.max(0, base + 0.22 * wave));
}

/** Google-style traffic colours. */
export const TRAFFIC_COLORS = { free: '#1fae63', slow: '#f5a524', heavy: '#e5484d', jam: '#8e1c22' };

export type TrafficBand = keyof typeof TRAFFIC_COLORS;

export function band(congestion: number): TrafficBand {
  return congestion < FREE ? 'free' : congestion < SLOW ? 'slow' : congestion < JAM ? 'heavy' : 'jam';
}

/** One road segment: [roadClass, hash, [lng, lat, lng, lat, ...]] */
export type RoadSegment = [number, number, number[]];

export interface RoadNetwork {
  attribution: string;
  segments: RoadSegment[];
}

let roads: Promise<RoadNetwork | null> | null = null;

/** The road network, downloaded once per page (about 1 MB; null if it cannot be had). */
export function loadRoads(): Promise<RoadNetwork | null> {
  roads ??= fetch('/data/traffic-roads.json')
    .then((r) => (r.ok ? (r.json() as Promise<RoadNetwork>) : null))
    .catch(() => null);
  return roads;
}

/** The roads as GeoJSON with each segment's congestion at epoch seconds t (`l`) and its band (`b`). */
export function trafficAt(net: RoadNetwork, t: number): FeatureCollection<LineString> {
  return {
    type: 'FeatureCollection',
    features: net.segments.map(([c, h, flat]) => {
      const coordinates: [number, number][] = [];
      for (let i = 0; i < flat.length; i += 2) coordinates.push([flat[i], flat[i + 1]]);
      const l = level(h, c, t);
      return { type: 'Feature', properties: { c, l, b: band(l) }, geometry: { type: 'LineString', coordinates } };
    }),
  };
}
