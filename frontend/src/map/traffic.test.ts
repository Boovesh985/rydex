import { describe, expect, it } from 'vitest';
import { band, demand, istHour, level, trafficAt } from './traffic';

// 2026-10-15 03:00 and 18:45 in India (UTC+05:30)
const NIGHT = Date.parse('2026-10-15T03:00:00+05:30') / 1000;
const RUSH = Date.parse('2026-10-15T18:45:00+05:30') / 1000;

// shared with simulator/tests/test_city.py: both implementations must give the same numbers
const VECTORS: [number, number, number, number][] = [
  [0.1234, 0, 1792065600, 0.4180877279789025],
  [0.8765, 1, 1792097100, 0.27399949864662293],
  [0.5, 2, 1792080000, 0.4072792929499385],
  [0.0001, 3, 1792000000, 0.12841262019387095],
  [0.6543, 1, 1792030000, 0.5304950859490551],
];

const mean = (xs: number[]) => xs.reduce((a, b) => a + b, 0) / xs.length;
const hashes = Array.from({ length: 100 }, (_, i) => i / 100);

describe('simulated traffic', () => {
  it('matches the simulator exactly', () => {
    for (const [h, c, t, expected] of VECTORS) expect(level(h, c, t)).toBeCloseTo(expected, 9);
  });

  it('follows Chennai’s day: quiet at night, jammed in the evening rush', () => {
    expect(istHour(RUSH)).toBeCloseTo(18.75, 6);
    expect(demand(18.75)).toBeGreaterThan(0.8);
    expect(demand(3)).toBeLessThan(0.2);
    expect(mean(hashes.map((h) => level(h, 1, RUSH)))).toBeGreaterThan(0.5);
    expect(mean(hashes.map((h) => level(h, 1, NIGHT)))).toBeLessThan(0.2);
    // the big roads jam first
    expect(mean(hashes.map((h) => level(h, 0, RUSH)))).toBeGreaterThan(mean(hashes.map((h) => level(h, 3, RUSH))));
  });

  it('colours like Google: green, orange, red, dark red', () => {
    expect([0, 0.39, 0.4, 0.7, 0.9].map(band)).toEqual(['free', 'free', 'slow', 'heavy', 'jam']);
  });

  it('turns the road file into coloured GeoJSON lines', () => {
    const fc = trafficAt({ attribution: 'OSM', segments: [[1, 0.9, [80.25, 13.06, 80.26, 13.05]]] }, RUSH);
    expect(fc.features).toHaveLength(1);
    expect(fc.features[0].geometry.coordinates).toEqual([
      [80.25, 13.06],
      [80.26, 13.05],
    ]);
    expect(fc.features[0].properties).toMatchObject({ c: 1, b: band(level(0.9, 1, RUSH)) });
  });
});
