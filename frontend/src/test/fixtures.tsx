import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router';
import type { Estimate, Ride } from '../api/types';

export function renderWithProviders(ui: ReactElement, { path = '/', route = '/' }: { path?: string; route?: string } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[route]}>
        <Routes>
          <Route path={path} element={ui} />
          <Route path="*" element={<p>elsewhere</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

export const CENTRAL = { lat: 13.0827, lng: 80.2707, address: 'Chennai Central, Park Town' };
export const MARINA = { lat: 13.05, lng: 80.2824, address: 'Marina Beach, Triplicane' };

export const estimate: Estimate = {
  pickup: CENTRAL,
  drop: MARINA,
  distanceM: 5200,
  durationS: 900,
  surgeMultiplier: 1,
  route: { polyline: null, source: 'ESTIMATED' },
  options: [
    { rideType: 'ECONOMY', fare: 124, etaMinutes: 3, driversNearby: 4 },
    { rideType: 'COMFORT', fare: 168, etaMinutes: 5, driversNearby: 2 },
    { rideType: 'XL', fare: 233, etaMinutes: null, driversNearby: 0 },
  ],
};

export function ride(overrides: Partial<Ride> = {}): Ride {
  return {
    id: 'ride-1',
    status: 'REQUESTED',
    rideType: 'ECONOMY',
    riderId: 'rider-1',
    driverId: null,
    pickup: CENTRAL,
    drop: MARINA,
    estFare: 124,
    finalFare: null,
    estDistanceM: 5200,
    estDurationS: 900,
    surgeMultiplier: 1,
    otp: '4821',
    requestedAt: '2026-10-14T10:00:00Z',
    assignedAt: null,
    arrivedAt: null,
    startedAt: null,
    completedAt: null,
    cancelledAt: null,
    cancelReason: null,
    cancelledBy: null,
    cancellationFee: null,
    paymentStatus: null,
    refundedAmount: null,
    timeline: [],
    ...overrides,
  };
}
