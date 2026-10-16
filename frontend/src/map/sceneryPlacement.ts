/**
 * Where realistic mode's scenery goes (ADR 0016): street lights along the main roads, trees along
 * the streets and in parks. Pure functions of the map's vector features, and deterministic (tied to
 * a world grid, not to tile boundaries), so a tree never jumps when the map reloads its tiles.
 */

export type LngLat = [number, number];

export interface Placed {
  lng: number;
  lat: number;
  /** compass degrees the thing faces (a lamp faces the road) */
  heading: number;
}

const M_PER_DEG_LAT = 110_540;
const mPerDegLng = (lat: number) => 111_320 * Math.cos((lat * Math.PI) / 180);

/** A stable pseudo-random number in [0, 1) for two integers. */
export function hash2(i: number, j: number): number {
  let h = Math.imul(i | 0, 0x27d4eb2d) ^ Math.imul(j | 0, 0x165667b1);
  h = Math.imul(h ^ (h >>> 15), 0x2c1b3c6d);
  h = Math.imul(h ^ (h >>> 12), 0x297a2d39);
  return ((h ^ (h >>> 15)) >>> 0) / 4294967296;
}

/** Metres east and north of a fixed point near Chennai: one grid for every road, whatever tile it came in. */
const REF_LNG = 80;
const REF_LAT = 13;
const GRID_KX = mPerDegLng(13.05);

/**
 * Points about every `spacing` metres along a line, set `offset` metres to its side (alternating
 * sides when `bothSides`), facing the line. A point goes where the line crosses a line of a fixed
 * world grid (east-west lines for a road running mostly north-south, and the other way round), so
 * the same road gives the same points however tiles cut or simplify it, and a rebuild never makes
 * a lamp jump or doubles a row.
 */
export function placeAlong(line: LngLat[], spacing: number, offset: number, bothSides: boolean): Placed[] {
  const out: Placed[] = [];
  const grid = spacing * 0.85;
  for (let i = 1; i < line.length; i++) {
    const ax = (line[i - 1][0] - REF_LNG) * GRID_KX;
    const ay = (line[i - 1][1] - REF_LAT) * M_PER_DEG_LAT;
    const dx = (line[i][0] - REF_LNG) * GRID_KX - ax;
    const dy = (line[i][1] - REF_LAT) * M_PER_DEG_LAT - ay;
    const seg = Math.hypot(dx, dy);
    if (seg === 0) continue;
    const heading = (Math.atan2(dx, dy) * 180) / Math.PI;
    const alongX = Math.abs(dx) >= Math.abs(dy);
    const a0 = alongX ? ax : ay;
    const d = alongX ? dx : dy;
    const lo = Math.min(a0, a0 + d);
    const hi = Math.max(a0, a0 + d);
    const ks: number[] = [];
    for (let k = Math.ceil(lo / grid); k * grid < hi; k++) ks.push(k);
    if (d < 0) ks.reverse();
    for (const k of ks) {
      const t = (k * grid - a0) / d;
      // sides by the world grid too (and not by which way the road was drawn)
      const side = (bothSides && Math.abs(k) % 2 === 1 ? -1 : 1) * (d < 0 ? -1 : 1);
      // perpendicular to the right of travel (+90 deg), or to the left
      const px = (dy / seg) * offset * side;
      const py = (-dx / seg) * offset * side;
      out.push({
        lng: REF_LNG + (ax + dx * t + px) / GRID_KX,
        lat: REF_LAT + (ay + dy * t + py) / M_PER_DEG_LAT,
        heading: (heading + (side > 0 ? 270 : 90) + 360) % 360,
      });
    }
  }
  return out;
}

function inRing(p: LngLat, ring: LngLat[]): boolean {
  let inside = false;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const [xi, yi] = ring[i];
    const [xj, yj] = ring[j];
    if (yi > p[1] !== yj > p[1] && p[0] < ((xj - xi) * (p[1] - yi)) / (yj - yi) + xi) inside = !inside;
  }
  return inside;
}

/** Inside the outer ring and not in a hole. */
export function inPolygon(p: LngLat, rings: LngLat[][]): boolean {
  if (rings.length === 0 || !inRing(p, rings[0])) return false;
  for (let k = 1; k < rings.length; k++) if (inRing(p, rings[k])) return false;
  return true;
}

/**
 * Trees scattered over a polygon (a park, a wood): one per cell of a world grid of `spacing`
 * metres, jittered within its cell, kept with probability `density`.
 */
export function scatterIn(rings: LngLat[][], spacing: number, density: number, max = 400): LngLat[] {
  const out: LngLat[] = [];
  if (rings.length === 0 || rings[0].length < 3) return out;
  let w = Infinity;
  let s = Infinity;
  let e = -Infinity;
  let n = -Infinity;
  for (const [x, y] of rings[0]) {
    w = Math.min(w, x);
    e = Math.max(e, x);
    s = Math.min(s, y);
    n = Math.max(n, y);
  }
  const dLat = spacing / M_PER_DEG_LAT;
  const dLng = spacing / mPerDegLng((s + n) / 2);
  for (let i = Math.floor(s / dLat); i <= Math.ceil(n / dLat); i++) {
    for (let j = Math.floor(w / dLng); j <= Math.ceil(e / dLng); j++) {
      if (hash2(i, j) > density) continue;
      const p: LngLat = [(j + 0.15 + 0.7 * hash2(j, i + 11)) * dLng, (i + 0.15 + 0.7 * hash2(i + 5, j)) * dLat];
      if (inPolygon(p, rings)) out.push(p);
      if (out.length >= max) return out;
    }
  }
  return out;
}

/** Drops points within ~`metres` of one already kept (tiles overlap at their edges). */
export function dedupe<T extends { lng: number; lat: number }>(points: T[], metres = 3): T[] {
  const seen = new Set<string>();
  const cell = metres / M_PER_DEG_LAT;
  return points.filter((p) => {
    const key = `${Math.round(p.lat / cell)}:${Math.round(p.lng / cell)}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

/** Metres between two points (flat earth, fine within a city). */
export function metresBetween(a: { lng: number; lat: number }, b: { lng: number; lat: number }): number {
  return Math.hypot((a.lng - b.lng) * mPerDegLng(a.lat), (a.lat - b.lat) * M_PER_DEG_LAT);
}
