import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { meApi, ridesApi } from '../../api/endpoints';
import type { GeoPoint } from '../../api/types';
import { CENTRAL, MARINA, renderWithProviders, ride } from '../../test/fixtures';
import { Shortcuts } from './Shortcuts';

vi.mock('../../api/endpoints', () => ({
  ridesApi: { list: vi.fn() },
  meApi: { places: vi.fn(), savePlace: vi.fn(), forgetPlace: vi.fn() },
}));

const HOME: GeoPoint = { lat: 13.0002, lng: 80.2668, address: 'Besant Nagar, Chennai' };
const TIDEL: GeoPoint = { lat: 12.9894, lng: 80.2486, address: 'Tidel Park, Taramani' };
const AIRPORT: GeoPoint = { lat: 12.99, lng: 80.17, address: 'Chennai Airport' };

describe('Shortcuts', () => {
  beforeEach(() => {
    vi.mocked(meApi.places).mockResolvedValue([{ kind: 'HOME', place: HOME }]);
    vi.mocked(ridesApi.list).mockResolvedValue({
      // newest first: the airport twice, home (saved already), Tidel Park
      items: [ride({ drop: AIRPORT }), ride({ drop: { ...AIRPORT, lat: 12.9901 } }), ride({ drop: HOME }), ride({ drop: TIDEL })],
      page: 0,
      size: 20,
      total: 4,
    });
  });

  it('offers Home and the last places the rider went, each one tap to set the drop', async () => {
    const onDrop = vi.fn();
    renderWithProviders(<Shortcuts pickup={CENTRAL} drop={null} onDrop={onDrop} />);

    const home = await screen.findByRole('button', { name: /Home.*Besant Nagar/ });
    await waitFor(() => expect(screen.getAllByRole('button', { name: /^Recent/ })).toHaveLength(2));
    expect(screen.getAllByRole('button', { name: /^Recent/ }).map((b) => b.getAttribute('aria-label'))).toEqual([
      'Recent: Chennai Airport',
      'Recent: Tidel Park, Taramani',
    ]);
    expect(screen.queryByRole('button', { name: /^Work/ })).not.toBeInTheDocument();

    await userEvent.click(home);
    expect(onDrop).toHaveBeenCalledWith(HOME);
  });

  it('saves a chosen drop as Work, and forgets a saved one', async () => {
    vi.mocked(meApi.savePlace).mockResolvedValue({ kind: 'WORK', place: MARINA });
    const { unmount } = renderWithProviders(<Shortcuts pickup={CENTRAL} drop={MARINA} onDrop={vi.fn()} />);

    await userEvent.click(await screen.findByRole('button', { name: 'Save as Work' }));
    expect(meApi.savePlace).toHaveBeenCalledWith('WORK', MARINA);
    expect(await screen.findByText('Saved as Work')).toBeInTheDocument();
    unmount();

    vi.mocked(meApi.forgetPlace).mockResolvedValue(undefined);
    renderWithProviders(<Shortcuts pickup={CENTRAL} drop={{ ...HOME, address: 'Pinned location' }} onDrop={vi.fn()} />);
    expect(await screen.findByText('Saved as Home')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Forget it' }));
    expect(meApi.forgetPlace).toHaveBeenCalledWith('HOME');
    expect(await screen.findByRole('button', { name: 'Save as Home' })).toBeInTheDocument();
  });
});
