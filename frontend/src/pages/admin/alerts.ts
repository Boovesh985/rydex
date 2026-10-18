import type { Payment, Ride } from '../../api/types';
import { inr, minutes, shortAddress } from '../../lib/format';

export interface Alert {
  key: string;
  /** what needs a look, in a sentence */
  title: string;
  detail: string;
  /** how long it has been going on (s): the longest first */
  ageS: number;
  /** the ride it is about; its driver, to ride along with */
  rideId: string;
  driverId: string | null;
}

const MIN = 60;

/**
 * What an operator should look at now (the admin's alerts): riders waiting too long, trips running
 * long, failed payments. From the rides in flight and the latest payments, at `now` (ms).
 */
export function alertsFor(rides: Ride[], payments: Payment[], now: number): Alert[] {
  const since = (iso: string | null) => (iso ? Math.max(0, (now - Date.parse(iso)) / 1000) : 0);
  const out: Alert[] = [];
  for (const r of rides) {
    const where = shortAddress(r.pickup.address);
    let age: number;
    switch (r.status) {
      case 'REQUESTED':
        // matching gives up after 90 s; longer means its outcome is late
        if ((age = since(r.requestedAt)) > 2 * MIN) out.push(alert(r, `Still searching for ${minutes(age)}`, `Rider at ${where}`, age));
        break;
      case 'DRIVER_ASSIGNED':
        if ((age = since(r.assignedAt)) > 15 * MIN) out.push(alert(r, `Driver on the way for ${minutes(age)}`, `Pickup at ${where}`, age));
        break;
      case 'DRIVER_ARRIVED':
        if ((age = since(r.arrivedAt)) > 5 * MIN) out.push(alert(r, `Waiting at the pickup for ${minutes(age)}`, `Pickup at ${where}`, age));
        break;
      case 'IN_PROGRESS':
        age = since(r.startedAt);
        if (age > 2 * r.estDurationS + 10 * MIN) {
          out.push(alert(r, `Trip running long: ${minutes(age)}`, `Planned ${minutes(r.estDurationS)} to ${shortAddress(r.drop.address)}`, age));
        }
        break;
    }
  }
  for (const p of payments) {
    const age = since(p.createdAt);
    if (p.status === 'FAILED' && age < 24 * 60 * MIN) {
      out.push({
        key: `payment:${p.id}`,
        title: `Payment failed · ${inr(p.amount)}`,
        detail: p.failureReason ?? (p.kind === 'CANCELLATION_FEE' ? 'Cancellation fee' : 'Ride fare'),
        ageS: age,
        rideId: p.rideId,
        driverId: null,
      });
    }
  }
  return out.sort((a, b) => b.ageS - a.ageS);
}

function alert(r: Ride, title: string, detail: string, ageS: number): Alert {
  return { key: `ride:${r.id}`, title, detail: `${detail}${r.simulated ? ' · NPC' : ''}`, ageS, rideId: r.id, driverId: r.driverId };
}
