import { describe, expect, it } from 'vitest';
import type { Payment } from '../../api/types';
import { ride } from '../../test/fixtures';
import { alertsFor } from './alerts';

const NOW = Date.parse('2026-10-18T10:00:00Z');
const ago = (min: number) => new Date(NOW - min * 60_000).toISOString();

const payment = (o: Partial<Payment>): Payment =>
  ({
    id: 'pay-1',
    rideId: 'ride-9',
    riderId: 'rider-1',
    driverId: null,
    kind: 'RIDE_FARE',
    status: 'SUCCEEDED',
    amount: 124,
    refundedAmount: 0,
    refundingAmount: 0,
    refundable: 0,
    currency: 'INR',
    provider: 'MOCK',
    providerRef: null,
    failureReason: null,
    createdAt: ago(3),
    paidAt: null,
    ...o,
  }) as Payment;

describe('alertsFor', () => {
  it('flags riders waiting too long and trips running long, the longest first', () => {
    const alerts = alertsFor(
      [
        ride({ id: 'searching', status: 'REQUESTED', requestedAt: ago(3) }),
        ride({ id: 'fresh', status: 'REQUESTED', requestedAt: ago(1) }),
        ride({ id: 'coming', status: 'DRIVER_ASSIGNED', driverId: 'd-1', assignedAt: ago(20) }),
        ride({ id: 'waiting', status: 'DRIVER_ARRIVED', driverId: 'd-2', arrivedAt: ago(7) }),
        ride({ id: 'long', status: 'IN_PROGRESS', driverId: 'd-3', startedAt: ago(45), estDurationS: 900 }),
        ride({ id: 'fine', status: 'IN_PROGRESS', driverId: 'd-4', startedAt: ago(30), estDurationS: 900 }),
      ],
      [],
      NOW,
    );

    expect(alerts.map((a) => a.rideId)).toEqual(['long', 'coming', 'waiting', 'searching']);
    expect(alerts[0].title).toBe('Trip running long: 45 min');
    expect(alerts[1]).toMatchObject({ title: 'Driver on the way for 20 min', driverId: 'd-1' });
    expect(alerts[3].driverId).toBeNull();
  });

  it('flags failed payments of the last day', () => {
    const alerts = alertsFor(
      [],
      [payment({ status: 'FAILED', failureReason: 'Card declined' }), payment({ id: 'old', status: 'FAILED', createdAt: ago(25 * 60) }), payment({ id: 'ok' })],
      NOW,
    );

    expect(alerts).toHaveLength(1);
    expect(alerts[0]).toMatchObject({ title: 'Payment failed · ₹124', detail: 'Card declined', rideId: 'ride-9' });
  });
});
