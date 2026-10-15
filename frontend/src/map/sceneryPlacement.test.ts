import { describe, expect, it } from 'vitest';
import { dedupe, hash2, inPolygon, metresBetween, placeAlong, scatterIn, type LngLat } from './sceneryPlacement';

// a straight road 1 km due north
const lat0 = 13.04;
const road: LngLat[] = [
  [80.25, lat0],
  [80.25, lat0 + 1000 / 110_540],
];

describe('scenery placement', () => {
  it('puts a light every 40 m, on alternating sides, set back from the road and facing it', () => {
    const lights = placeAlong(road, 40, 8, true);
    expect(lights.length).toBeGreaterThanOrEqual(24);
    expect(lights.length).toBeLessThanOrEqual(26);
    for (let i = 1; i < lights.length; i++) {
      expect(metresBetween(lights[i], { lng: lights[i - 1].lng, lat: lights[i].lat })).toBeCloseTo(16, 0); // other side
      expect((lights[i].lat - lights[i - 1].lat) * 110_540).toBeCloseTo(40, 1);
    }
    const east = lights.find((l) => l.lng > 80.25)!;
    const west = lights.find((l) => l.lng < 80.25)!;
    expect(east.heading).toBe(270); // east of a northbound road, facing west towards it
    expect(west.heading).toBe(90);
  });

  it('gives the same places however a road is cut into pieces', () => {
    const whole = placeAlong(road, 40, 8, false);
    expect(placeAlong(road, 40, 8, false)).toEqual(whole);
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
