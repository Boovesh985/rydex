import { describe, expect, it } from 'vitest';
import { dedupe, hash2, inPolygon, metresBetween, placeAlong, scatterIn, type LngLat } from './sceneryPlacement';

// a straight road 1 km due north
const lat0 = 13.04;
const road: LngLat[] = [
  [80.25, lat0],
  [80.25, lat0 + 1000 / 110_540],
];

describe('scenery placement', () => {
  it('puts a light about every 40 m, on alternating sides, set back from the road and facing it', () => {
    const lights = placeAlong(road, 40, 8, true);
    // a world grid line every 34 m (0.85 x spacing) for a road running straight north
    expect(lights.length).toBeGreaterThanOrEqual(28);
    expect(lights.length).toBeLessThanOrEqual(30);
    for (let i = 1; i < lights.length; i++) {
      expect(metresBetween(lights[i], { lng: lights[i - 1].lng, lat: lights[i].lat })).toBeCloseTo(16, 0); // other side
      expect((lights[i].lat - lights[i - 1].lat) * 110_540).toBeCloseTo(34, 1);
    }
    const east = lights.find((l) => l.lng > 80.25)!;
    const west = lights.find((l) => l.lng < 80.25)!;
    expect(east.heading).toBe(270); // east of a northbound road, facing west towards it
    expect(west.heading).toBe(90);
  });

  it('gives the same places however a road is cut into pieces or which way it runs', () => {
    const whole = placeAlong(road, 40, 8, true);
    const cut: LngLat = [80.25, lat0 + 377 / 110_540];
    const pieces = [...placeAlong([road[0], cut], 40, 8, true), ...placeAlong([cut, road[1]], 40, 8, true)];
    expect(pieces).toHaveLength(whole.length);
    pieces.forEach((p, i) => {
      expect(p.lat).toBeCloseTo(whole[i].lat, 9);
      expect(p.lng).toBeCloseTo(whole[i].lng, 9);
    });
    // the same road drawn the other way: the same spots (each now on the other side of travel)
    const reversed = placeAlong([road[1], road[0]], 40, 8, true);
    const key = (p: { lat: number; lng: number }) => `${p.lat.toFixed(6)},${p.lng.toFixed(6)}`;
    expect(new Set(reversed.map(key))).toEqual(new Set(whole.map(key)));
  });

  it('scatters trees inside a park and never in its pond', () => {
    const d = 200 / 110_540;
    const park: LngLat[] = [
      [80.25, lat0],
      [80.25 + d, lat0],
      [80.25 + d, lat0 + d],
      [80.25, lat0 + d],
      [80.25, lat0],
    ];
    const pond: LngLat[] = [
      [80.25 + d / 4, lat0 + d / 4],
      [80.25 + (3 * d) / 4, lat0 + d / 4],
      [80.25 + (3 * d) / 4, lat0 + (3 * d) / 4],
      [80.25 + d / 4, lat0 + (3 * d) / 4],
      [80.25 + d / 4, lat0 + d / 4],
    ];
    const trees = scatterIn([park, pond], 15, 0.7);
    expect(trees.length).toBeGreaterThan(40);
    for (const t of trees) {
      expect(inPolygon(t, [park])).toBe(true);
      expect(inPolygon(t, [pond])).toBe(false);
    }
    expect(scatterIn([park, pond], 15, 0.7)).toEqual(trees);
  });

  it('keeps one of two things at the same spot', () => {
    const p = { lng: 80.25, lat: 13.04 };
    expect(dedupe([p, { lng: 80.25000001, lat: 13.04 }, { lng: 80.2501, lat: 13.04 }])).toHaveLength(2);
  });

  it('hashes evenly and repeatably', () => {
    const xs = Array.from({ length: 1000 }, (_, i) => hash2(i, 3));
    expect(xs.every((x) => x >= 0 && x < 1)).toBe(true);
    expect(xs.reduce((a, b) => a + b, 0) / xs.length).toBeCloseTo(0.5, 1);
    expect(hash2(12, 34)).toBe(hash2(12, 34));
  });
});
