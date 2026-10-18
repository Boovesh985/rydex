import type { GeoPoint, Ride, RideType } from '../../api/types';

/** A trip to book again (Try again after no driver was found), handed to the booking screen. */
export interface RetryTrip {
  pickup: GeoPoint;
  drop: GeoPoint;
  rideType: RideType;
}

/** The router state that opens the booking screen with this ride's trip filled in. */
export const retryState = (r: Ride): { retry: RetryTrip } => ({ retry: { pickup: named(r.pickup), drop: named(r.drop), rideType: r.rideType } });

/** a point booked without a name reads as a pin, as one tapped on the map does */
const named = (p: GeoPoint): GeoPoint => ({ ...p, address: p.address || 'Pinned location' });

/** The trip in a router state, if it carries one (anything else, e.g. an old history entry, is ignored). */
export function retryFrom(state: unknown): RetryTrip | null {
  const retry = (state as { retry?: Partial<RetryTrip> } | null)?.retry;
  const point = (p: unknown): p is GeoPoint => !!p && typeof (p as GeoPoint).lat === 'number' && typeof (p as GeoPoint).lng === 'number';
  return retry && point(retry.pickup) && point(retry.drop) && typeof retry.rideType === 'string' ? (retry as RetryTrip) : null;
}
