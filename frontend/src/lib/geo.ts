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
