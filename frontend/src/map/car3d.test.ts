import { describe, expect, it } from 'vitest';
import { RIDE_TYPES } from '../api/types';
import { vehicleShape } from './car3d';

describe('vehicle models', () => {
  it('builds every ride type to its real size', () => {
    for (const type of RIDE_TYPES) {
      const { spec, bounds } = vehicleShape(type);
      const length = bounds.max.z - bounds.min.z;
      const width = bounds.max.x - bounds.min.x;
      expect(length / spec.length, type).toBeGreaterThan(0.9);
      expect(length / spec.length, type).toBeLessThan(1.1);
      expect(width / spec.width, type).toBeLessThan(1.3);
      expect(bounds.max.y / spec.height, type).toBeGreaterThan(0.85);
      expect(bounds.max.y / spec.height, type).toBeLessThan(1.25);
      expect(bounds.min.y, type).toBeGreaterThan(-0.01); // nothing below the road
    }
  });

  it('has two wheels on a bike, three on an auto and four on a cab, and steers with the front ones', () => {
    const wheels = (type: (typeof RIDE_TYPES)[number]) => vehicleShape(type).wheelAt.length;
    expect([wheels('BIKE'), wheels('AUTO'), wheels('ECONOMY'), wheels('PREMIUM')]).toEqual([2, 3, 4, 4]);
    for (const type of RIDE_TYPES) {
      const { wheelAt, steers } = vehicleShape(type);
      expect(steers).toHaveLength(wheelAt.length);
      // forward is -z: every steering wheel is ahead of every fixed one
      const front = wheelAt.filter((_, i) => steers[i]).map(([, z]) => z);
      const rear = wheelAt.filter((_, i) => !steers[i]).map(([, z]) => z);
      expect(front.length, type).toBeGreaterThan(0);
      expect(Math.max(...front), type).toBeLessThan(Math.min(...rear));
    }
  });

  it('seats a driver on bikes and autos, with room for passengers, and only a bike leans into turns', () => {
    const bike = vehicleShape('BIKE');
    const auto = vehicleShape('AUTO');
    const cab = vehicleShape('COMFORT');
    expect(bike.people && auto.people).toBeTruthy();
    expect(bike.passengers && auto.passengers).toBe(true);
    expect(cab.people).toBeNull();
    expect(cab.passengers).toBe(false);
    // the bike's rider sits up above the seat, the auto's driver stays under the canopy
    expect(bike.people!.max.y).toBeGreaterThan(1.6);
    expect(auto.people!.max.y).toBeLessThan(auto.bounds.max.y);
    expect([bike.leans, auto.leans, cab.leans]).toEqual([true, false, false]);
  });
});
