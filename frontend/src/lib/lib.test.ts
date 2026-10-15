import { describe, expect, it } from 'vitest';
import { distance, inr, minutes, shortAddress } from './format';
import { along, bearing, haversine, lerpHeading, matchOnRoute, metersPerPixel, PathIndex, pathLength, type LatLng, type RouteMatch } from './geo';
import { decodePolyline, encodePolyline } from './polyline';

describe('polyline', () => {
  it('decodes the reference example (precision 5)', () => {
    // https://developers.google.com/maps/documentation/utilities/polylinealgorithm
    expect(decodePolyline('_p~iF~ps|U_ulLnnqC_mqNvxq`@', 5)).toEqual([
      [38.5, -120.2],
      [40.7, -120.95],
      [43.252, -126.453],
    ]);
  });

  it('round-trips OSRM polyline6 coordinates around Chennai', () => {
    const pts: LatLng[] = [
      [13.082681, 80.270718],
      [13.06, 80.2824],
      [12.994112, 80.170867],
    ];
    expect(decodePolyline(encodePolyline(pts))).toEqual(pts);
  });
});

describe('geo', () => {
  const central: LatLng = [13.0827, 80.2707];
  const north: LatLng = [13.0827 + 1000 / 111_195, 80.2707];

  it('measures distance and bearing', () => {
    expect(haversine(central, north)).toBeCloseTo(1000, -1);
    expect(bearing(central, north)).toBeCloseTo(0, 3);
    expect(bearing(north, central)).toBeCloseTo(180, 3);
  });

  it('walks along a path and clamps at its end', () => {
    const path: LatLng[] = [central, north, [north[0], north[1] + 0.01]];
    const half = along(path, 500);
    expect(haversine(central, half.point)).toBeCloseTo(500, -1);
    expect(half.done).toBe(false);
    const end = along(path, pathLength(path) + 50);
    expect(end.point).toEqual(path[2]);
    expect(end.done).toBe(true);
    expect(end.heading).toBeCloseTo(90, 0);
  });

  it('turns the short way round', () => {
    expect(lerpHeading(350, 10, 0.5)).toBeCloseTo(0, 6);
    expect(lerpHeading(10, 350, 0.5)).toBeCloseTo(0, 6);
    expect(lerpHeading(90, 180, 1)).toBeCloseTo(180, 6);
  });
});

describe('format', () => {
  it('formats rupees, minutes and distances', () => {
    expect(inr(339)).toBe('₹339');
    expect(inr(null)).toBe('—');
    expect(minutes(20)).toBe('1 min');
    expect(minutes(125)).toBe('2 min');
    expect(minutes(3900)).toBe('1 h 05 min');
    expect(distance(843)).toBe('840 m');
    expect(distance(1234)).toBe('1.2 km');
    expect(distance(22111)).toBe('22 km');
  });

  it('shortens addresses', () => {
    expect(shortAddress('Chennai Central, Park Town, Chennai')).toBe('Chennai Central');
    expect(shortAddress(null)).toBe('Pinned location');
  });
});

describe('PathIndex', () => {
  // an L: 1 km north, then 1 km east
  const corner: LatLng = [13.009, 80.2];
  const path = new PathIndex([[13.0, 80.2], corner, [13.009, 80.20925]]);

  it('finds points along the path, with the segment heading', () => {
    expect(path.length).toBeGreaterThan(1990);
    expect(path.length).toBeLessThan(2010);
    const half = path.at(500);
    expect(half.point[0]).toBeCloseTo(13.0045, 3);
    expect(half.heading).toBeCloseTo(0, 0);
    const later = path.at(1500);
    expect(later.point[0]).toBeCloseTo(13.009, 4);
    expect(later.heading).toBeCloseTo(90, 0);
    expect(path.at(99_999).point).toEqual([13.009, 80.20925]);
  });

  it('projects a fix beside the road onto it', () => {
    const fix: LatLng = [13.0045, 80.2002]; // ~20 m east of the first leg, halfway up
    const { along, offset } = path.project(fix);
    expect(along).toBeGreaterThan(480);
    expect(along).toBeLessThan(520);
    expect(offset).toBeGreaterThan(15);
    expect(offset).toBeLessThan(30);
  });

  it('gives a metre-per-pixel scale that halves with every zoom level', () => {
    expect(metersPerPixel(17, 0)).toBeCloseTo(0.597, 2);
    expect(metersPerPixel(18, 13) / metersPerPixel(17, 13)).toBeCloseTo(0.5, 5);
  });
});

describe('matchOnRoute', () => {
  // up one side of a divided road for 1 km, a U-turn, and back down the other side 20 m away
  const lane = 20 / 111_320;
  const route = new PathIndex([
    [13.0, 80.2],
    [13.009, 80.2],
    [13.009, 80.2 + lane],
    [13.0, 80.2 + lane],
  ]);
  const drive = (fixes: LatLng[]) => {
    let m: RouteMatch | null = null;
    const along: number[] = [];
    for (const f of fixes) {
      m = matchOnRoute(route, f, m);
      along.push(Math.round(m.along));
    }
    return { m: m!, along };
  };

  it('keeps a car on the way back on the way back, even where the two passes are side by side', () => {
    // past the U-turn, heading south: each fix is 10 m from the northbound side too
    const { along } = drive([
      [13.0, 80.2],
      [13.008, 80.2],
      [13.009, 80.2 + lane / 2],
      [13.006, 80.2 + lane * 0.5],
      [13.003, 80.2 + lane * 0.5],
    ]);
    for (let i = 1; i < along.length; i++) expect(along[i]).toBeGreaterThanOrEqual(along[i - 1]);
    expect(along.at(-1)).toBeGreaterThan(1300);
  });

  it('never goes backwards, and holds its place while the car is off the route', () => {
    const { m, along } = drive([
      [13.005, 80.2],
      [13.004, 80.2], // a fix a little behind (GPS noise)
      [13.005, 80.21], // a kilometre off to the east
    ]);
    expect(along[1]).toBe(along[0]);
    expect(m.onRoute).toBe(false);
    expect(m.along).toBeCloseTo(along[0], 0);
    expect(m.offset).toBeGreaterThan(900);
  });
});
