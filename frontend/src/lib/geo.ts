export type LatLng = [number, number];

const R = 6_371_000;
const rad = (d: number) => (d * Math.PI) / 180;
const deg = (r: number) => (r * 180) / Math.PI;

export function haversine(a: LatLng, b: LatLng): number {
  const dLat = rad(b[0] - a[0]);
  const dLng = rad(b[1] - a[1]);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(rad(a[0])) * Math.cos(rad(b[0])) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}

/** Compass bearing from a to b, 0-360 degrees (0 = north). */
export function bearing(a: LatLng, b: LatLng): number {
  const y = Math.sin(rad(b[1] - a[1])) * Math.cos(rad(b[0]));
  const x =
    Math.cos(rad(a[0])) * Math.sin(rad(b[0])) - Math.sin(rad(a[0])) * Math.cos(rad(b[0])) * Math.cos(rad(b[1] - a[1]));
  return (deg(Math.atan2(y, x)) + 360) % 360;
}

export const lerp = (a: LatLng, b: LatLng, t: number): LatLng => [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t];

/** Shortest turn between two headings, so a marker rotating 350° -> 10° turns 20°, not 340°. */
export function lerpHeading(from: number, to: number, t: number): number {
  const diff = ((to - from + 540) % 360) - 180;
  return (from + diff * t + 360) % 360;
}

export function pathLength(path: LatLng[]): number {
  let total = 0;
  for (let i = 1; i < path.length; i++) total += haversine(path[i - 1], path[i]);
  return total;
}

/** The point `meters` along a path, with the heading of that segment; clamps to the ends. */
export function along(path: LatLng[], meters: number): { point: LatLng; heading: number; done: boolean } {
  if (path.length === 1) return { point: path[0], heading: 0, done: true };
  let left = Math.max(0, meters);
  for (let i = 1; i < path.length; i++) {
    const seg = haversine(path[i - 1], path[i]);
    if (left <= seg) {
      return { point: lerp(path[i - 1], path[i], seg === 0 ? 1 : left / seg), heading: bearing(path[i - 1], path[i]), done: false };
    }
    left -= seg;
  }
  const n = path.length;
  return { point: path[n - 1], heading: bearing(path[n - 2], path[n - 1]), done: true };
}

/**
 * A path with cumulative distances, for quick "where is the point d metres along" lookups and
 * for snapping GPS fixes to the road the route follows.
 */
export class PathIndex {
  readonly path: LatLng[];
  readonly length: number;
  private readonly cumulative: number[];

  constructor(path: LatLng[]) {
    this.path = path;
    this.cumulative = [0];
    for (let i = 1; i < path.length; i++) this.cumulative.push(this.cumulative[i - 1] + haversine(path[i - 1], path[i]));
    this.length = this.cumulative[this.cumulative.length - 1] ?? 0;
  }

  /** The point `meters` along the path and the heading of its segment (binary search). */
  at(meters: number): { point: LatLng; heading: number } {
    const p = this.path;
    if (p.length === 1) return { point: p[0], heading: 0 };
    const d = Math.min(Math.max(0, meters), this.length);
    let lo = 1;
    let hi = p.length - 1;
    while (lo < hi) {
      const mid = (lo + hi) >> 1;
      if (this.cumulative[mid] < d) lo = mid + 1;
      else hi = mid;
    }
    const seg = this.cumulative[lo] - this.cumulative[lo - 1];
    const t = seg === 0 ? 1 : (d - this.cumulative[lo - 1]) / seg;
    return { point: lerp(p[lo - 1], p[lo], t), heading: bearing(p[lo - 1], p[lo]) };
  }

  /**
   * The closest point of the path to `point`: how far along the path it is, and how far off
   * the path `point` lies (metres, flat-earth approximation, fine at city scale). `within`
   * limits the search to that stretch of the path (metres along it).
   */
  project(point: LatLng, within?: { from: number; to: number }): { along: number; offset: number } {
    const p = this.path;
    if (p.length < 2) return { along: 0, offset: p.length ? haversine(p[0], point) : Infinity };
    const kx = Math.cos(rad(point[0])) * 111_320;
    const ky = 110_540;
    let best = { along: 0, offset: Infinity };
    for (let i = 1; i < p.length; i++) {
      if (within && (this.cumulative[i] < within.from || this.cumulative[i - 1] > within.to)) continue;
      const ax = (p[i - 1][1] - point[1]) * kx;
      const ay = (p[i - 1][0] - point[0]) * ky;
      const bx = (p[i][1] - point[1]) * kx;
      const by = (p[i][0] - point[0]) * ky;
      const dx = bx - ax;
      const dy = by - ay;
      const len2 = dx * dx + dy * dy;
      let t = len2 === 0 ? 0 : Math.min(1, Math.max(0, -(ax * dx + ay * dy) / len2));
      if (within) {
        // only the part of this segment inside the stretch searched
        const c0 = this.cumulative[i - 1];
        const seg = this.cumulative[i] - c0;
        if (seg > 0) t = Math.min(Math.max(t, (within.from - c0) / seg), (within.to - c0) / seg);
        t = Math.min(1, Math.max(0, t));
      }
      const cx = ax + dx * t;
      const cy = ay + dy * t;
      const offset = Math.hypot(cx, cy);
      if (offset < best.offset) {
        best = { along: this.cumulative[i - 1] + (this.cumulative[i] - this.cumulative[i - 1]) * t, offset };
      }
    }
    return best;
  }
}

/** A fix further than this from the route is off it (GPS noise on a matched road stays well inside). */
export const OFF_ROUTE_M = 45;

export interface RouteMatch {
  /** metres along the route; never goes backwards for one vehicle on one route */
  along: number;
  /** metres between the fix and the route */
  offset: number;
  onRoute: boolean;
  /** the fix this match was last on the route at */
  point: LatLng;
}

/**
 * Where a moving vehicle is along its route. Each fix is looked for on the stretch just ahead of
 * the previous match (as far as it could have driven), so a route that passes the same spot twice
 * (a U-turn, the two sides of a divided road) cannot pull it back to the earlier pass, and the
 * distance along only ever grows. Off the route, the last match is kept.
 */
export function matchOnRoute(index: PathIndex, point: LatLng, prev: RouteMatch | null): RouteMatch {
  if (!prev) {
    const m = index.project(point);
    return { along: m.along, offset: m.offset, onRoute: m.offset <= OFF_ROUTE_M, point };
  }
  // a road between two fixes is rarely 2.5x longer than the straight line between them
  const reach = haversine(prev.point, point) * 2.5 + 300;
  let m = index.project(point, { from: prev.along - 30, to: prev.along + reach });
  if (m.offset > OFF_ROUTE_M) {
    // back on the route further ahead than expected (a dropped connection, a short cut)
    const anywhere = index.project(point);
    if (anywhere.offset <= OFF_ROUTE_M && anywhere.along >= prev.along) m = anywhere;
  }
  if (m.offset > OFF_ROUTE_M) return { ...prev, offset: m.offset, onRoute: false };
  return { along: Math.max(prev.along, m.along), offset: m.offset, onRoute: true, point };
}

/** Metres per screen pixel at a zoom level and latitude (Web Mercator, 512 px tiles). */
export const metersPerPixel = (zoom: number, lat: number) => (40_075_016.686 * Math.cos(rad(lat))) / (512 * 2 ** zoom);

/** The more recent of two timestamped fixes (`at` is ISO-8601), or whichever exists. */
export function newest<T extends { at: string }>(a: T | null | undefined, b: T | null | undefined): T | null {
  if (!a) return b ?? null;
  if (!b) return a;
  return Date.parse(b.at) > Date.parse(a.at) ? b : a;
}
