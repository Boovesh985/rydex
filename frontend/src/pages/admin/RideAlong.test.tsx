import { act, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { adminApi, trackingApi } from '../../api/endpoints';
import type { DriverProfile, LiveLocation } from '../../api/types';
import { renderWithProviders, ride } from '../../test/fixtures';
import { RideAlong } from './RideAlong';

vi.mock('../../api/endpoints', () => ({
  adminApi: { driver: vi.fn() },
  trackingApi: { location: vi.fn() },
}));

const pushes = vi.hoisted(() => new Map<string, (body: unknown) => void>());
vi.mock('../../realtime/hooks', () => ({
  useSubscription: (destination: string | null, handler: (body: unknown) => void) => {
    if (destination) pushes.set(destination, handler);
  },
  useOnReconnect: () => undefined,
}));

const bike: DriverProfile = {
  id: 'driver-27',
  name: 'Driver 27',
  vehicleType: 'BIKE',
  vehicleNumber: 'TN 06 BH 1999',
  vehicleModel: 'Bajaj Pulsar 150',
  rating: 4.9,
  status: 'ON_TRIP',
  currentRideId: 'ride-1',
  searchable: false,
  lastLocation: { lat: 13.06, lng: 80.25, at: '2026-10-17T10:00:00Z' },
  updatedAt: '2026-10-17T10:00:00Z',
};

describe('RideAlong', () => {
  beforeEach(() => {
    pushes.clear();
    vi.mocked(adminApi.driver).mockResolvedValue(bike);
  });

  it('shows the vehicle and its trip live, and closes', async () => {
    const onClose = vi.fn();
    vi.mocked(trackingApi.location).mockRejectedValue(new Error('no fix yet'));
    const trip = ride({ status: 'IN_PROGRESS', rideType: 'BIKE', driverId: 'driver-27', estDistanceM: 5000 });
    renderWithProviders(<RideAlong driverId="driver-27" duty="trip" speedKmh={32.4} ride={trip} onClose={onClose} />);

    expect(await screen.findByText('Bajaj Pulsar 150 · Bike')).toBeInTheDocument();
    expect(screen.getByText(/Carrying a rider · 32 km\/h/)).toBeInTheDocument();
    expect(screen.getByText('Chennai Central, Park Town')).toBeInTheDocument();
    expect(screen.getByText('Marina Beach, Triplicane')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Open the trip page/ })).toHaveAttribute('href', '/trips/ride-1');

    // a live fix 2 km from the drop on a 5 km trip: 60% there
    const fix: LiveLocation = { rideId: 'ride-1', driverId: 'driver-27', lat: 13.07, lng: 80.27, heading: 90, speed: 9, phase: 'TO_DROP', etaS: 300, distanceM: 2000, etaEstimated: false, at: '2026-10-17T10:00:05Z' };
    act(() => pushes.get('/topic/rides/ride-1/location')!(fix));
    expect(screen.getByRole('status', { name: 'On trip: 5 min' })).toBeInTheDocument();
    expect(screen.getByRole('progressbar', { name: 'Trip progress' })).toHaveAttribute('aria-valuenow', '60');

    await userEvent.click(screen.getByRole('button', { name: 'Stop riding along' }));
    expect(onClose).toHaveBeenCalled();
  });

  it('says so when the vehicle has no rider', async () => {
    renderWithProviders(<RideAlong driverId="driver-27" duty="free" speedKmh={null} ride={null} onClose={() => undefined} />);

    expect(await screen.findByText('Driver 27')).toBeInTheDocument();
    expect(screen.getByText('Free, cruising for a request')).toBeInTheDocument();
    expect(screen.getByText(/No rider yet/)).toBeInTheDocument();
  });
});
