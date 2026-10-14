import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Offer } from '../../api/types';
import { CENTRAL, MARINA } from '../../test/fixtures';
import { OfferModal } from './OfferModal';

const now = new Date('2026-10-14T10:00:00Z').getTime();
const offer: Offer = {
  rideId: 'ride-1',
  rideType: 'COMFORT',
  pickup: CENTRAL,
  drop: MARINA,
  estFare: 168,
  estDistanceM: 5200,
  estDurationS: 900,
  pickupEtaS: 85,
  distanceToPickupM: 420,
  attempt: 1,
  offeredAt: new Date(now).toISOString(),
  expiresAt: new Date(now + 15_000).toISOString(),
  secondsLeft: 15,
};

describe('OfferModal', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.setSystemTime(now);
  });
  afterEach(() => vi.useRealTimers());

  it('shows the ride and counts down from 15 seconds', () => {
    render(<OfferModal offer={offer} busy={null} error="" onAccept={vi.fn()} onReject={vi.fn()} />);

    expect(screen.getByRole('dialog', { name: 'New Comfort ride' })).toBeInTheDocument();
    expect(screen.getByText('₹168')).toBeInTheDocument();
    expect(screen.getByText(/Pickup 420 m away/)).toBeInTheDocument();
    expect(screen.getByLabelText('15 seconds left')).toBeInTheDocument();

    act(() => vi.advanceTimersByTime(10_000));
    expect(screen.getByLabelText('5 seconds left')).toBeInTheDocument();

    act(() => vi.advanceTimersByTime(6_000));
    expect(screen.getByRole('button', { name: 'Accept ride' })).toBeDisabled();
  });

  it('accepts and declines', async () => {
    const onAccept = vi.fn();
    const onReject = vi.fn();
    render(<OfferModal offer={offer} busy={null} error="" onAccept={onAccept} onReject={onReject} />);
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });

    await user.click(screen.getByRole('button', { name: 'Accept ride' }));
    await user.click(screen.getByRole('button', { name: 'Decline' }));

    expect(onAccept).toHaveBeenCalledOnce();
    expect(onReject).toHaveBeenCalledOnce();
  });
});
