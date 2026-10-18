import type { LiveLocation, Ride } from '../api/types';
import { distance, inr, minutes, rideTypeLabel, shortAddress, vehicleNoun, when } from '../lib/format';

/** The one number for a ride, as the trip page and the operations map's ride-along show it. */
export function tripMeter(r: Ride, location: LiveLocation | null): { label: string; value: string; unit?: string; sub?: string; tone?: 'auto' | 'go' } {
  switch (r.status) {
    case 'REQUESTED':
      return { label: 'Finding a driver', value: '···', sub: `${rideTypeLabel[r.rideType]} · ${inr(r.estFare)}` };
    case 'DRIVER_ASSIGNED':
      return location
        ? {
            label: 'To pickup',
            value: minutes(location.etaS).replace(' min', ''),
            unit: 'min',
            sub: `${distance(location.distanceM)} from ${shortAddress(r.pickup.address)}`,
          }
        : { label: 'To pickup', value: '···', sub: `Waiting for the ${vehicleNoun(r.rideType)}’s position` };
    case 'DRIVER_ARRIVED':
      return { label: 'At pickup', value: 'WAIT', tone: 'go', sub: `Waiting at ${shortAddress(r.pickup.address)}` };
    case 'IN_PROGRESS':
      return location && location.phase === 'TO_DROP'
        ? {
            label: 'On trip',
            value: minutes(location.etaS).replace(' min', ''),
            unit: 'min',
            sub: `${distance(location.distanceM)} to ${shortAddress(r.drop.address)}`,
          }
        : { label: 'On trip', value: '···', sub: `To ${shortAddress(r.drop.address)}` };
    case 'COMPLETED':
      return { label: 'Completed', value: inr(r.finalFare ?? r.estFare), tone: 'go', sub: r.completedAt ? `Dropped ${when(r.completedAt)}` : undefined };
    case 'CANCELLED':
      return {
        label: 'Cancelled',
        value: (r.cancellationFee ?? 0) > 0 ? inr(r.cancellationFee) : '—',
        sub: r.cancelReason ?? `By ${r.cancelledBy?.toLowerCase() ?? 'the system'}`,
      };
    case 'NO_DRIVER_FOUND':
      return { label: 'No driver found', value: '—', sub: 'Nobody accepted in time' };
  }
}
