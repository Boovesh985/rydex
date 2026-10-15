import type { LiveDriver, Ride } from '../../api/types';
import { rideTypeLabel, shortAddress } from '../../lib/format';
import type { CarDuty, DispatchLine, FleetCar, WaitingRider } from '../../map/MapView';

export interface CityView {
  fleet: FleetCar[];
  riders: WaitingRider[];
  lines: DispatchLine[];
  counts: { free: number; toPickup: number; carrying: number; searching: number; waiting: number };
}

const DUTY_TEXT: Record<CarDuty, string> = { free: 'free', pickup: 'heading to a pickup', trip: 'carrying a rider' };

/**
 * The living city on the admin map (ADR 0016), from the live drivers (driver-service presence) and
 * the rides in flight (ride-service): what each car is doing, who is waiting where, and who is
 * heading where.
 */
export function cityView(drivers: LiveDriver[], rides: Ride[]): CityView {
  const byDriver = new Map<string, Ride>();
  for (const r of rides) if (r.driverId && ['DRIVER_ASSIGNED', 'DRIVER_ARRIVED', 'IN_PROGRESS'].includes(r.status)) byDriver.set(r.driverId, r);

  const counts = { free: 0, toPickup: 0, carrying: 0, searching: 0, waiting: 0 };
  const fleet: FleetCar[] = [];
  const lines: DispatchLine[] = [];
  for (const d of drivers) {
    if (d.status !== 'AVAILABLE' && d.status !== 'ON_TRIP') continue;
    const ride = byDriver.get(d.driverId);
    const duty: CarDuty = ride ? (ride.status === 'IN_PROGRESS' ? 'trip' : 'pickup') : d.status === 'ON_TRIP' ? 'pickup' : 'free';
    if (duty === 'free') counts.free++;
    else if (duty === 'pickup') counts.toPickup++;
    else counts.carrying++;
    fleet.push({
      id: d.driverId,
      lat: d.lat,
      lng: d.lng,
      heading: d.heading,
      type: d.rideType,
      duty,
      label: `${d.rideType ? rideTypeLabel[d.rideType] : 'Driver'} · ${DUTY_TEXT[duty]}`,
    });
    if (ride && ride.status === 'DRIVER_ASSIGNED') {
      lines.push({ id: ride.id, from: [d.lat, d.lng], to: [ride.pickup.lat, ride.pickup.lng], kind: 'pickup' });
    } else if (ride && ride.status === 'IN_PROGRESS') {
      lines.push({ id: ride.id, from: [d.lat, d.lng], to: [ride.drop.lat, ride.drop.lng], kind: 'trip' });
    }
  }

  const riders: WaitingRider[] = [];
  for (const r of rides) {
    const state = r.status === 'REQUESTED' ? 'searching' : r.status === 'DRIVER_ASSIGNED' ? 'waiting' : r.status === 'DRIVER_ARRIVED' ? 'boarding' : null;
    if (!state) continue;
    if (state === 'searching') counts.searching++;
    else counts.waiting++;
    const doing = state === 'searching' ? 'looking for a driver' : state === 'waiting' ? 'driver on the way' : 'getting in';
    riders.push({
      id: r.id,
      lat: r.pickup.lat,
      lng: r.pickup.lng,
      state,
      label: `${r.simulated ? 'NPC rider' : 'Rider'} ${doing} · ${shortAddress(r.pickup.address)} → ${shortAddress(r.drop.address)}`,
    });
  }
  return { fleet, riders, lines, counts };
}
