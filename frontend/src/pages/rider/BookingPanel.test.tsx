import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { placesApi, ridesApi } from '../../api/endpoints';
import { ApiError } from '../../api/http';
import { CENTRAL, MARINA, estimate, renderWithProviders, ride } from '../../test/fixtures';
import { BookingPanel } from './BookingPanel';

vi.mock('../../api/endpoints', () => ({
  ridesApi: { estimate: vi.fn(), book: vi.fn() },
  placesApi: { search: vi.fn() },
}));

function setup(props: Partial<Parameters<typeof BookingPanel>[0]> = {}) {
  const handlers = {
    onPickup: vi.fn(),
    onDrop: vi.fn(),
    onSwap: vi.fn(),
    onFocusField: vi.fn(),
    onEstimate: vi.fn(),
    onBooked: vi.fn(),
  };
  renderWithProviders(<BookingPanel pickup={CENTRAL} drop={MARINA} {...handlers} {...props} />);
  return handlers;
}

describe('BookingPanel', () => {
  beforeEach(() => {
    vi.mocked(ridesApi.estimate).mockResolvedValue(estimate);
  });

  it('prices every ride type and says when no car is nearby', async () => {
    setup();

    expect(await screen.findByRole('radio', { name: /Economy/ })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByRole('radio', { name: /Comfort.*₹168.*5 min away/ })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: /XL.*No cars nearby/ })).toBeInTheDocument();
    // a bike taxi seats one, an auto three
    expect(screen.getByRole('radio', { name: /^Bike1Bike taxi.*₹58.*2 min away/ })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: /^Auto3Auto rickshaw.*₹90.*No autos nearby/ })).toBeInTheDocument();
    expect(screen.getByRole('status', { name: /Fare, fixed upfront: ₹124/ })).toBeInTheDocument();
    expect(ridesApi.estimate).toHaveBeenCalledWith(CENTRAL, MARINA);
  });

  it('says when a ride type may come as a better class at its fare', async () => {
    vi.mocked(ridesApi.estimate).mockResolvedValue({
      ...estimate,
      options: estimate.options.map((o) => (o.rideType === 'XL' ? o : o.rideType === 'ECONOMY' ? { ...o, etaMinutes: 4, driversNearby: 0, upgradeTo: 'COMFORT' } : o)),
    });
    setup();

    expect(await screen.findByRole('radio', { name: /Economy.*₹124.*4 min away.*May come as a Comfort/ })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: /^Comfort/ })).not.toHaveTextContent(/May come as/);
  });

  it('starts on the ride type it is handed (trying again after no driver was found)', async () => {
    setup({ initialType: 'COMFORT' });

    expect(await screen.findByRole('radio', { name: /Comfort/ })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByRole('button', { name: 'Book Comfort' })).toBeEnabled();
  });

  it('books the chosen ride type with an idempotency key', async () => {
    const booked = ride({ rideType: 'COMFORT' });
    vi.mocked(ridesApi.book).mockResolvedValue(booked);
    const { onBooked } = setup();
    const user = userEvent.setup();

    await user.click(await screen.findByRole('radio', { name: /Comfort/ }));
    expect(screen.getByRole('status', { name: /₹168/ })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Book Comfort' }));

    await waitFor(() => expect(onBooked).toHaveBeenCalledWith(booked));
    const [pickup, drop, type, key] = vi.mocked(ridesApi.book).mock.calls[0];
    expect([pickup, drop, type]).toEqual([CENTRAL, MARINA, 'COMFORT']);
    expect(key).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('keeps the same idempotency key when a booking attempt is retried', async () => {
    vi.mocked(ridesApi.book).mockRejectedValueOnce(new TypeError('network down')).mockResolvedValueOnce(ride());
    const { onBooked } = setup();
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Book Economy' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Cannot reach Rydex');
    await user.click(screen.getByRole('button', { name: 'Book Economy' }));

    await waitFor(() => expect(onBooked).toHaveBeenCalled());
    const keys = vi.mocked(ridesApi.book).mock.calls.map((c) => c[3]);
    expect(keys[0]).toBe(keys[1]);
  });

  it('shows why booking was refused', async () => {
    vi.mocked(ridesApi.book).mockRejectedValue(new ApiError(409, 'Conflict', 'You already have a ride in progress'));
    setup();
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Book Economy' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('You already have a ride in progress');
  });

  it('suggests places while typing and picks one with the keyboard', async () => {
    vi.mocked(placesApi.search).mockResolvedValue([
      { name: 'Marina Beach', address: 'Marina Beach, Triplicane, Chennai', lat: 13.05, lng: 80.2824 },
      { name: 'Marina Mall', address: 'OMR, Chennai', lat: 12.84, lng: 80.23 },
    ]);
    const { onDrop } = setup({ drop: null });
    const user = userEvent.setup();

    await user.type(screen.getByRole('combobox', { name: 'Drop' }), 'mari');
    expect(await screen.findByRole('option', { name: /Marina Mall/ })).toBeInTheDocument();
    await user.keyboard('{ArrowDown}{ArrowDown}{Enter}');

    expect(onDrop).toHaveBeenCalledWith({ lat: 12.84, lng: 80.23, address: 'Marina Mall, OMR, Chennai' });
    expect(ridesApi.estimate).not.toHaveBeenCalled();
  });
});

describe('retryFrom', () => {
  it('reads the trip a Try again hands over, and ignores anything else', async () => {
    const { retryFrom, retryState } = await import('./retryTrip');
    const trip = retryState(ride({ rideType: 'AUTO' }));

    expect(retryFrom(trip)).toEqual({ pickup: CENTRAL, drop: MARINA, rideType: 'AUTO' });
    expect(retryFrom(null)).toBeNull();
    expect(retryFrom({ retry: { pickup: CENTRAL } })).toBeNull();
    expect(retryFrom({ from: '/history' })).toBeNull();
  });
});

describe('retryState', () => {
  it('names a point booked without an address as a pin', async () => {
    const { retryState } = await import('./retryTrip');
    const { retry } = retryState(ride({ pickup: { lat: 12.9165, lng: 79.1325, address: null } }));

    expect(retry.pickup).toEqual({ lat: 12.9165, lng: 79.1325, address: 'Pinned location' });
    expect(retry.drop).toEqual(MARINA);
  });
});
