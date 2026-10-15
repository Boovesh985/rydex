import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { RealisticHud, SpeedDial, TripProgress } from './Realistic';

describe('realistic mode overlay', () => {
  it('shows the status, speed and the camera controls', async () => {
    const handlers = { onFollow: vi.fn(), onOverview: vi.fn(), onNight: vi.fn(), onExit: vi.fn() };
    render(
      <RealisticHud eyebrow="On the way" value="4" unit="min" sub="1.2 km to Marina" speedKmh={32.4} following night={false} {...handlers}>
        <p>Driver 07</p>
      </RealisticHud>,
    );
    const user = userEvent.setup();

    expect(screen.getByRole('status', { name: 'On the way: 4 min' })).toHaveTextContent('1.2 km to Marina');
    expect(screen.getByRole('img', { name: 'Speed 32 km/h' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Following the car' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText('Driver 07')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Show the whole route' }));
    await user.click(screen.getByRole('button', { name: 'Switch to night' }));
    await user.click(screen.getByRole('button', { name: 'Exit realistic mode' }));
    expect(handlers.onOverview).toHaveBeenCalledOnce();
    expect(handlers.onNight).toHaveBeenCalledOnce();
    expect(handlers.onExit).toHaveBeenCalledOnce();
  });

  it('never shows a negative speed and caps the trip progress', () => {
    render(
      <>
        <SpeedDial kmh={-3} />
        <TripProgress progress={1.4} from="Central" to="Marina" />
      </>,
    );
    expect(screen.getByRole('img', { name: 'Speed 0 km/h' })).toBeInTheDocument();
    expect(screen.getByRole('progressbar', { name: 'Trip progress' })).toHaveAttribute('aria-valuenow', '100');
  });
});
