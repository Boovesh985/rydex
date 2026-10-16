import { describe, expect, it } from 'vitest';
import { widthAt, worldWidth } from './theme';

/** Metres per pixel in Chennai at a zoom. */
const mpp = (zoom: number) => (156543.03 * Math.cos((13.06 * Math.PI) / 180)) / 2 ** zoom;

describe('street-level widths', () => {
  it("reads the base style's zoom curves", () => {
    const positron = ['interpolate', ['exponential', 1.55], ['zoom'], 13, 1.8, 20, 20];
    expect(widthAt(positron, 12)).toBe(1.8);
    expect(widthAt(positron, 13)).toBe(1.8);
    expect(widthAt(positron, 20)).toBe(20);
    expect(widthAt(positron, 24)).toBe(20);
    expect(widthAt(positron, 16)).toBeGreaterThan(1.8);
    expect(widthAt(4, 18)).toBe(4);
    expect(widthAt(['get', 'width'], 18)).toBeNull();
  });

  it('keeps the map look zoomed out and the real width on the ground at street level', () => {
    const road = worldWidth([[12, 1.5], [16, 6]], 12);
    expect(widthAt(road, 12)).toBeCloseTo(1.5, 5);
    expect(widthAt(road, 16)).toBeCloseTo(6, 5);
    for (const zoom of [20, 21, 22, 23]) {
      const metres = widthAt(road, zoom)! * mpp(zoom);
      expect(metres).toBeGreaterThan(12 * 0.95);
      expect(metres).toBeLessThan(12 * 1.25);
    }
    expect(widthAt(road, 24)! * mpp(24)).toBeCloseTo(12, 3);
  });
});
