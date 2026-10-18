import { act, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { adminApi, ridesApi, trackingApi } from '../../api/endpoints';
import type { DriverProfile, LiveDriver } from '../../api/types';
import type { FleetCar } from '../../map/MapView';
import { renderWithProviders } from '../../test/fixtures';
import { AdminDashboard } from './AdminDashboard';

vi.mock('../../api/endpoints', () => ({
  adminApi: { liveDrivers: vi.fn(), driver: vi.fn(), stats: vi.fn(), grid: vi.fn(), demand: vi.fn(), payments: vi.fn(), refund: vi.fn(), searchDrivers: vi.fn() },
  ridesApi: { all: vi.fn() },
  trackingApi: { location: vi.fn() },
}));

// the map needs WebGL; the fleet is a list of buttons here, the followed vehicle a label
vi.mock('../../map/MapView', () => ({
  BaseMap: ({ children }: { children: React.ReactNode }) => <div data-testid="map">{children}</div>,
  MapMoves: () => null,
  DemandHeat: () => null,
  GridOverlay: () => null,
  DispatchLines: () => null,
  RiderMarkers: () => null,
  RememberView: () => null,
  Fleet: ({ cars, hide, onSelect }: { cars: FleetCar[]; hide: string | null; onSelect: (id: string) => void }) => (
    <>
      {cars
        .filter((c) => c.id !== hide)
        .map((c) => (
          <button key={c.id} onClick={() => onSelect(c.id)}>
            {`pick ${c.id}`}
          </button>
        ))}
    </>
  ),
  AnimatedCar: ({ label }: { label: string }) => <span data-testid="followed">{label}</span>,
}));

vi.mock('../../realtime/hooks', () => ({
  useSubscription: () => undefined,
  useOnReconnect: () => undefined,
}));

const live = (status: LiveDriver['status']): LiveDriver => ({
  driverId: 'driver-27',
  lat: 13.05,
  lng: 80.25,
  heading: 90,
  speed: 8,
  geohash: 'tf3z7p',
  status,
  rideType: 'BIKE',
  at: '2026-10-18T10:00:00Z',
});

const profile: DriverProfile = {
  id: 'driver-27',
  name: 'Driver 27',
  vehicleType: 'BIKE',
  vehicleNumber: 'TN 06 BH 1999',
  vehicleModel: 'Bajaj Pulsar 150',
  rating: 4.9,
  status: 'AVAILABLE',
  currentRideId: null,
  searchable: true,
  lastLocation: null,
  updatedAt: '2026-10-18T10:00:00Z',
};

describe('AdminDashboard ride-along', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.mocked(adminApi.liveDrivers).mockResolvedValue([live('AVAILABLE')]);
    vi.mocked(adminApi.driver).mockResolvedValue(profile);
    vi.mocked(adminApi.stats).mockReturnValue(new Promise(() => undefined));
    vi.mocked(ridesApi.all).mockResolvedValue({ items: [], page: 0, size: 100, total: 0 });
    vi.mocked(trackingApi.location).mockRejectedValue(new Error('no fix'));
    vi.mocked(adminApi.payments).mockResolvedValue({ items: [], page: 0, size: 50, total: 0 });
    vi.mocked(adminApi.searchDrivers).mockResolvedValue([
      profile,
      { ...profile, id: 'driver-31', name: 'Driver 31', status: 'OFFLINE' },
      // "free" in the database, but its app went quiet: not on the map, nothing to ride along with
      { ...profile, id: 'driver-ghost', name: 'Ghost Driver', status: 'AVAILABLE' },
    ]);
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('rides along with a picked vehicle until Esc', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderWithProviders(<AdminDashboard />);

    await user.click(await screen.findByRole('button', { name: 'pick driver-27' }));
    expect(screen.getByTestId('followed')).toHaveTextContent('Bike · free');
    expect(await screen.findByText('Bajaj Pulsar 150 · Bike')).toBeInTheDocument();
    // the followed vehicle is drawn on its own, not as part of the fleet
    expect(screen.queryByRole('button', { name: 'pick driver-27' })).not.toBeInTheDocument();

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('region', { name: 'Ride along' })).not.toBeInTheDocument();
    expect(screen.queryByTestId('followed')).not.toBeInTheDocument();
  });

  it('finds a vehicle by plate and rides along with it', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderWithProviders(<AdminDashboard />);

    await user.type(screen.getByPlaceholderText(/Find a vehicle/), 'tn06');
    const found = await screen.findByRole('region', { name: 'Find a vehicle' });
    expect(await within(found).findByText('Driver 31')).toBeInTheDocument();
    expect(within(found).getAllByText('Offline')).toHaveLength(2);
    expect(within(found).getAllByRole('button', { name: /Ride along/ })).toHaveLength(1);
    expect(adminApi.searchDrivers).toHaveBeenCalledWith('tn06', expect.anything());

    await user.click(within(found).getByRole('button', { name: /Ride along/ }));
    expect(await screen.findByRole('region', { name: 'Ride along' })).toBeInTheDocument();
    expect(screen.getByTestId('followed')).toHaveTextContent('Bike · free');
  });

  it('lets go of a driver who goes offline, and stays let go when they come back', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderWithProviders(<AdminDashboard />);
    await user.click(await screen.findByRole('button', { name: 'pick driver-27' }));
    expect(screen.getByRole('region', { name: 'Ride along' })).toBeInTheDocument();

    vi.mocked(adminApi.liveDrivers).mockResolvedValue([live('OFFLINE')]);
    await act(() => vi.advanceTimersByTimeAsync(3100));
    expect(screen.queryByRole('region', { name: 'Ride along' })).not.toBeInTheDocument();

    vi.mocked(adminApi.liveDrivers).mockResolvedValue([live('AVAILABLE')]);
    await act(() => vi.advanceTimersByTimeAsync(3100));
    expect(await screen.findByRole('button', { name: 'pick driver-27' })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Ride along' })).not.toBeInTheDocument();
    expect(screen.queryByTestId('followed')).not.toBeInTheDocument();
  });
});
