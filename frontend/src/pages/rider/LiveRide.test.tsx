import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { driversApi, paymentsApi, placesApi, ridesApi, trackingApi } from '../../api/endpoints';
import { ApiError } from '../../api/http';
import type { LiveLocation, Payment } from '../../api/types';
import { CENTRAL, estimate, MARINA, renderWithProviders, ride } from '../../test/fixtures';
import { LiveRide } from './LiveRide';

vi.mock('../../api/endpoints', () => ({
  ridesApi: { get: vi.fn(), cancel: vi.fn(), estimate: vi.fn(), book: vi.fn() },
  trackingApi: { location: vi.fn() },
  driversApi: { forRide: vi.fn(), nearby: vi.fn().mockResolvedValue([]) },
  placesApi: { route: vi.fn() },
  paymentsApi: { forRide: vi.fn(), verify: vi.fn(), retry: vi.fn() },
}));

// MapLibre needs WebGL and a real layout engine; the map is covered by the browser end-to-end test
vi.mock('../../map/MapView', () => ({
  BaseMap: ({ children }: { children: React.ReactNode }) => <div data-testid="map">{children}</div>,
  PickupPin: () => null,
  DropPin: () => null,
  RouteLine: () => null,
  FitTo: () => null,
  Fleet: () => null,
  AnimatedCar: ({ lat, lng }: { lat: number; lng: number }) => <span data-testid="car">{`${lat},${lng}`}</span>,
}));

const pushes = vi.hoisted(() => new Map<string, (body: unknown) => void>());
vi.mock('../../realtime/hooks', () => ({
  useSubscription: (destination: string | null, handler: (body: unknown) => void) => {
    if (destination) pushes.set(destination, handler);
  },
  useOnReconnect: () => undefined,
}));

const location = (o: Partial<LiveLocation> = {}): LiveLocation => ({
  rideId: 'ride-1',
  driverId: 'driver-1',
  lat: 13.09,
  lng: 80.27,
  heading: 180,
  speed: 8,
  phase: 'TO_PICKUP',
  etaS: 240,
  distanceM: 1500,
  etaEstimated: false,
  at: '2026-10-14T10:01:00Z',
  ...o,
});

function show() {
  renderWithProviders(<LiveRide />, { path: '/ride/:rideId', route: '/ride/ride-1' });
}

describe('LiveRide', () => {
  beforeEach(() => {
    pushes.clear();
    vi.mocked(placesApi.route).mockResolvedValue({ distanceM: 1500, durationS: 240, polyline: null, source: 'ESTIMATED' });
    vi.mocked(driversApi.forRide).mockResolvedValue({
      id: 'driver-1',
      name: 'Driver 07',
      vehicleType: 'ECONOMY',
      vehicleNumber: 'TN 08 HX 1259',
      vehicleModel: 'Tata Tiago',
      rating: 4.8,
    });
  });

  it('is finding a driver until one is assigned, and cancelling then is free', async () => {
    vi.mocked(ridesApi.get).mockResolvedValue(ride());
    vi.mocked(ridesApi.cancel).mockResolvedValue(ride({ status: 'CANCELLED', cancelledBy: 'RIDER', cancelReason: 'Cancelled by rider' }));
    show();
    const user = userEvent.setup();

    expect(await screen.findByRole('status', { name: /Finding your driver/ })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cancel ride' }));
    expect(screen.getByText('Cancelling now is free.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Yes, cancel' }));

    expect(await screen.findByText('Ride cancelled')).toBeInTheDocument();
    expect(ridesApi.cancel).toHaveBeenCalledWith('ride-1', 'Cancelled by rider');
    expect(screen.getByRole('button', { name: 'Book another ride' })).toBeInTheDocument();
  });

  it('shows the driver, the PIN and a live ETA that follows location pushes', async () => {
    vi.mocked(ridesApi.get).mockResolvedValue(ride({ status: 'DRIVER_ASSIGNED', driverId: 'driver-1', assignedAt: '2026-10-14T10:00:20Z' }));
    vi.mocked(trackingApi.location).mockResolvedValue(location());
    show();

    expect(await screen.findByRole('status', { name: 'Driver arriving: 4 min' })).toBeInTheDocument();
    expect(await screen.findByText('Driver 07')).toBeInTheDocument();
    expect(screen.getByText('TN 08 HX 1259')).toBeInTheDocument();
    expect(screen.getByLabelText('Ride PIN 4 8 2 1')).toBeInTheDocument();
    expect(screen.getByTestId('car')).toHaveTextContent('13.09,80.27');

    await waitFor(() => expect(pushes.has('/topic/rides/ride-1/location')).toBe(true));
    act(() => pushes.get('/topic/rides/ride-1/location')!(location({ lat: 13.085, etaS: 60, distanceM: 300 })));

    expect(screen.getByRole('status', { name: 'Driver arriving: 1 min' })).toBeInTheDocument();
    expect(screen.getByText('300 m away')).toBeInTheDocument();
    expect(screen.getByTestId('car')).toHaveTextContent('13.085,80.27');
  });

  it('says when the driver is a free upgrade', async () => {
    // an Economy ride taken by a sedan: no hatchback was free (the fixture's driver drives a Tata Tiago)
    vi.mocked(driversApi.forRide).mockResolvedValue({
      id: 'driver-1',
      name: 'Driver 14',
      vehicleType: 'COMFORT',
      vehicleNumber: 'TN 15 OB 1518',
      vehicleModel: 'Honda City',
      rating: 4.7,
    });
    vi.mocked(ridesApi.get).mockResolvedValue(ride({ status: 'DRIVER_ASSIGNED', driverId: 'driver-1' }));
    vi.mocked(trackingApi.location).mockResolvedValue(location());
    show();

    expect(await screen.findByText('Free upgrade: a Comfort at the Economy fare')).toBeInTheDocument();
  });

  it('warns that cancelling after the driver arrived costs a fee', async () => {
    vi.mocked(ridesApi.get).mockResolvedValue(ride({ status: 'DRIVER_ARRIVED', driverId: 'driver-1' }));
    vi.mocked(trackingApi.location).mockResolvedValue(location({ phase: 'AT_PICKUP', etaS: 0 }));
    show();
    const user = userEvent.setup();

    expect(await screen.findByRole('status', { name: /Your driver is here/ })).toBeInTheDocument();
    // the PIN is what the driver needs now
    expect(screen.getByRole('group', { name: 'Your ride PIN' })).toHaveTextContent('Tell your driver this PIN');
    expect(screen.getByLabelText('Ride PIN 4 8 2 1')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cancel ride' }));

    expect(screen.getByText(/a cancellation fee applies/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancel and pay fee' })).toBeInTheDocument();
  });

  it('shows the fare and the payment once the trip is over', async () => {
    vi.mocked(ridesApi.get).mockResolvedValue(
      ride({
        status: 'COMPLETED',
        driverId: 'driver-1',
        finalFare: 124,
        completedAt: '2026-10-14T10:20:00Z',
        paymentStatus: 'SUCCEEDED',
      }),
    );
    vi.mocked(paymentsApi.forRide).mockResolvedValue({
      id: 'pay-1',
      rideId: 'ride-1',
      kind: 'RIDE_FARE',
      status: 'SUCCEEDED',
      amount: 124,
      refundedAmount: 0,
      refundable: 124,
      provider: 'MOCK',
      checkout: null,
    } as Payment);
    show();

    expect(await screen.findByRole('status', { name: 'You have arrived: ₹124' })).toBeInTheDocument();
    expect(await screen.findByText('Paid')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Pay/ })).not.toBeInTheDocument();
    expect(trackingApi.location).not.toHaveBeenCalled();
  });

  it('asks the rider to pay in Razorpay Checkout when the payment awaits them', async () => {
    vi.mocked(ridesApi.get).mockResolvedValue(ride({ status: 'COMPLETED', driverId: 'driver-1', finalFare: 124 }));
    vi.mocked(paymentsApi.forRide).mockResolvedValue({
      id: 'pay-1',
      rideId: 'ride-1',
      kind: 'RIDE_FARE',
      status: 'PENDING',
      amount: 124,
      refundedAmount: 0,
      refundable: 0,
      provider: 'RAZORPAY',
      checkout: { keyId: 'rzp_test_x', orderId: 'order_1', amount: 12400, currency: 'INR', name: 'Rydex', description: 'Trip' },
    } as Payment);
    show();

    expect(await screen.findByRole('button', { name: 'Pay ₹124' })).toBeInTheDocument();
    expect(screen.getByText('Due')).toBeInTheDocument();
    expect(screen.getByText(/Test mode: choose Netbanking/)).toBeInTheDocument();
  });

  it('says so when the ride is not the rider’s', async () => {
    vi.mocked(ridesApi.get).mockRejectedValue(new ApiError(404, 'Not Found', 'Ride not found'));
    show();

    expect(await screen.findByRole('alert')).toHaveTextContent('This ride does not exist or is not yours.');
  });
});

describe('LiveRide when no driver was found', () => {
  const unmatched = ride({ rideType: 'COMFORT', status: 'NO_DRIVER_FOUND' });

  /** the ride screen, and the booking screen showing the trip it was handed */
  function showWithBooking() {
    function Booking() {
      return <p>booking {JSON.stringify(useLocation().state)}</p>;
    }
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={['/ride/ride-1']}>
          <Routes>
            <Route path="/ride/:rideId" element={<LiveRide />} />
            <Route path="/ride" element={<Booking />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
  }

  beforeEach(() => {
    vi.mocked(ridesApi.get).mockImplementation(async (id) => (id === 'ride-1' ? unmatched : ride({ id, rideType: 'BIKE' })));
    vi.mocked(ridesApi.estimate).mockResolvedValue(estimate);
  });

  it('offers the ride types with a driver nearby now, quickest first, and books one in a tap', async () => {
    vi.mocked(ridesApi.book).mockResolvedValue(ride({ id: 'ride-2', rideType: 'BIKE' }));
    showWithBooking();
    const user = userEvent.setup();

    const nearby = await screen.findByRole('region', { name: 'Rides nearby now' });
    // the fixture: bike 2 min, economy 3 min, comfort 5 min; auto and XL have nobody nearby
    expect(
      within(nearby)
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(['Book Bike', 'Book Economy', 'Book Comfort']);
    expect(within(nearby).getByText(/5 min away · a car came free/)).toBeInTheDocument();
    expect(ridesApi.estimate).toHaveBeenCalledWith(CENTRAL, MARINA);

    await user.click(within(nearby).getByRole('button', { name: 'Book Bike' }));
    expect(ridesApi.book).toHaveBeenCalledWith(CENTRAL, MARINA, 'BIKE', expect.any(String));
    await waitFor(() => expect(ridesApi.get).toHaveBeenCalledWith('ride-2'));
  });

  it('tries again from the same trip', async () => {
    showWithBooking();
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Try again' }));

    expect(screen.getByText(/^booking /).textContent).toBe(`booking ${JSON.stringify({ retry: { pickup: CENTRAL, drop: MARINA, rideType: 'COMFORT' } })}`);
  });
});
