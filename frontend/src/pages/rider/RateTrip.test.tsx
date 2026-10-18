import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ridesApi } from '../../api/endpoints';
import { renderWithProviders, ride } from '../../test/fixtures';
import { RateTrip } from './RateTrip';

vi.mock('../../api/endpoints', () => ({ ridesApi: { rate: vi.fn() } }));

describe('RateTrip', () => {
  it('rates the trip with stars and an optional word, once', async () => {
    const done = ride({ status: 'COMPLETED', driverId: 'driver-1' });
    vi.mocked(ridesApi.rate).mockResolvedValue({ ...done, riderRating: 4, riderComment: 'Smooth' });
    const user = userEvent.setup();
    const { unmount } = renderWithProviders(<RateTrip ride={done} />);

    expect(screen.queryByRole('button', { name: /^Rate/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole('radio', { name: '4 stars: Good' }));
    await user.type(screen.getByPlaceholderText(/Anything to add/), 'Smooth');
    await user.click(screen.getByRole('button', { name: 'Rate 4 stars' }));

    expect(ridesApi.rate).toHaveBeenCalledWith('ride-1', 4, 'Smooth');
    unmount();
    renderWithProviders(<RateTrip ride={{ ...done, riderRating: 4 }} />);
    expect(screen.getByLabelText('You rated this trip 4 out of 5')).toBeInTheDocument();
    expect(screen.queryByRole('radiogroup')).not.toBeInTheDocument();
  });
});
