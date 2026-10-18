import { render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { RouteError } from './RouteError';

function Broken({ error }: { error: Error }): never {
  throw error;
}

function show(error: Error) {
  const router = createMemoryRouter([{ path: '/', element: <Broken error={error} />, errorElement: <RouteError /> }]);
  render(<RouterProvider router={router} />);
}

describe('RouteError', () => {
  const reload = vi.fn();
  const location = window.location;
  beforeEach(() => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    sessionStorage.clear();
    Object.defineProperty(window, 'location', { configurable: true, value: { ...location, reload } });
  });
  afterEach(() => {
    Object.defineProperty(window, 'location', { configurable: true, value: location });
    reload.mockReset();
    vi.restoreAllMocks();
  });

  it('shows a way out instead of the developer page', () => {
    show(new TypeError("Cannot read properties of undefined (reading 'lat')"));

    expect(screen.getByRole('heading', { name: 'This screen hit a snag.' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reload' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Home' })).toHaveAttribute('href', '/');
    expect(screen.getByText(/reading 'lat'/)).toBeInTheDocument();
    expect(screen.queryByText(/Hey developer/)).not.toBeInTheDocument();
    expect(reload).not.toHaveBeenCalled();
  });

  it('reloads once when the app was updated under the tab', () => {
    show(new TypeError('Failed to fetch dynamically imported module: http://localhost:5173/src/pages/rider/LiveRide.tsx'));

    expect(screen.getByRole('heading', { name: 'Loading the new version…' })).toBeInTheDocument();
    expect(reload).toHaveBeenCalledOnce();
  });

  it('does not reload in a loop', () => {
    sessionStorage.setItem('rydex.reloadedAt', String(Date.now()));
    show(new TypeError('Failed to fetch dynamically imported module: /src/pages/TripPage.tsx'));

    expect(reload).not.toHaveBeenCalled();
    expect(screen.getByRole('heading', { name: 'This tab needs a reload.' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reload' })).toBeInTheDocument();
  });
});
