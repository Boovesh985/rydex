import { describe, expect, it } from 'vitest';
import type { LiveDriver } from '../../api/types';
import { ride } from '../../test/fixtures';
import { cityView } from './city';

const driver = (id: string, status: LiveDriver['status'], lat = 13.05): LiveDriver => ({
  driverId: id,
  lat,
  lng: 80.25,
  heading: 90,
  speed: 8,
  geohash: 'tf3zf8',
  status,
  rideType: 'ECONOMY',
  at: '2026-10-15T10:00:00Z',
});

describe('the living city on the admin map', () => {
  it('tells free cars from cars heading to a pickup and cars carrying a rider', () => {
    const rides = [
      ride({ id: 'r-assigned', status: 'DRIVER_ASSIGNED', driverId: 'd-pickup', simulated: true }),
      ride({ id: 'r-trip', status: 'IN_PROGRESS', driverId: 'd-trip' }),
      ride({ id: 'r-search', status: 'REQUESTED' }),
      ride({ id: 'r-here', status: 'DRIVER_ARRIVED', driverId: 'd-here' }),
    ];
    const view = cityView(
      [driver('d-free', 'AVAILABLE'), driver('d-pickup', 'ON_TRIP'), driver('d-trip', 'ON_TRIP'), driver('d-here', 'ON_TRIP'), driver('d-off', 'OFFLINE')],
      rides,
    );

    expect(view.fleet.map((c) => [c.id, c.duty])).toEqual([
      ['d-free', 'free'],
      ['d-pickup', 'pickup'],
      ['d-trip', 'trip'],
      ['d-here', 'pickup'],
    ]);
    expect(view.counts).toEqual({ free: 1, toPickup: 2, carrying: 1, searching: 1, waiting: 2 });
    expect(view.riders.map((r) => [r.id, r.state])).toEqual([
      ['r-assigned', 'waiting'],
      ['r-search', 'searching'],
      ['r-here', 'boarding'],
    ]);
    expect(view.riders[0].label).toMatch(/^NPC rider driver on the way/);
    // the car on its way draws a line to the pickup, the busy one to the drop
    expect(view.lines.map((l) => [l.id, l.kind])).toEqual([
      ['r-assigned', 'pickup'],
      ['r-trip', 'trip'],
    ]);
    expect(view.lines[0].to).toEqual([rides[0].pickup.lat, rides[0].pickup.lng]);
  });
});
