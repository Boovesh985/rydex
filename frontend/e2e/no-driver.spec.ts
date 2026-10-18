import { expect, test, type APIRequestContext } from '@playwright/test';

/**
 * When no driver can be found: the rider is told so, offered the rides that are free near the pickup
 * now (one tap books one), and Try again opens the booking screen with the same trip. Far from the
 * city (Vellore), so neither the living city nor other tests interfere; a bike ride never upgrades,
 * so the auto standing at the pickup cannot take it, and matching gives up after its 90 s search.
 */

const PASSWORD = 'E2e-only-password-1';
const VELLORE = { lat: 12.9165, lng: 79.1325, address: 'Vellore Fort' };
const DROP = { lat: 12.934, lng: 79.146, address: 'Katpadi' };

async function register(request: APIRequestContext, role: 'RIDER' | 'DRIVER') {
  const email = `e2e-${role.toLowerCase()}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@test.rydex.dev`;
  const data = { name: `E2E ${role === 'RIDER' ? 'Rider' : 'Driver'}`, email, password: PASSWORD, role };
  let res = await request.post('/api/auth/register', { data });
  // sign-ups share a 20-a-minute budget per IP with the smoke test that runs before in CI
  for (let attempt = 0; res.status() === 429 && attempt < 2; attempt++) {
    await new Promise((resolve) => setTimeout(resolve, (Number(res.headers()['retry-after']) || 30) * 1000 + 500));
    res = await request.post('/api/auth/register', { data });
  }
  expect(res.status(), await res.text()).toBe(201);
  const body = await res.json();
  return { email, token: body.accessToken as string };
}

const auth = (token: string) => ({ Authorization: `Bearer ${token}` });

test('no driver found: the rides free nearby, one tap to book, and Try again keeps the trip', async ({ page, request }) => {
  test.setTimeout(240_000);

  // an auto waiting at the pickup, heartbeating like the driver app (a silent driver leaves the search index after 30 s)
  const driver = await register(request, 'DRIVER');
  expect(
    (
      await request.put('/api/drivers/me', {
        headers: auth(driver.token),
        data: { vehicleType: 'AUTO', vehicleNumber: `E2E A ${Date.now() % 100000}`, vehicleModel: 'Bajaj RE' },
      })
    ).ok(),
  ).toBe(true);
  expect((await request.patch('/api/drivers/me/status', { headers: auth(driver.token), data: { status: 'AVAILABLE', ...VELLORE } })).ok()).toBe(true);
  const heartbeat = setInterval(() => {
    void request.post('/api/drivers/me/location', { headers: auth(driver.token), data: { lat: VELLORE.lat, lng: VELLORE.lng } }).catch(() => undefined);
  }, 3000);

  try {
    // the rider signs in and books a bike taxi there (over the API: the booking screen's search is Nominatim's)
    const rider = await register(request, 'RIDER');
    await page.goto('/login');
    await page.getByLabel('Email').fill(rider.email);
    await page.getByLabel('Password', { exact: true }).fill(PASSWORD);
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByRole('heading', { name: 'Where to?' })).toBeVisible();
    const booked = await request.post('/api/rides', {
      headers: { ...auth(rider.token), 'Idempotency-Key': crypto.randomUUID() },
      data: { pickup: VELLORE, drop: DROP, rideType: 'BIKE' },
    });
    expect(booked.status(), await booked.text()).toBe(201);
    const rideId = (await booked.json()).id as string;

    await page.goto(`/ride/${rideId}`);
    await expect(page.getByRole('status', { name: /Finding your driver/ })).toBeVisible();

    // matching gives up after 90 s
    await expect(page.getByText('No drivers available', { exact: true }).first()).toBeVisible({ timeout: 150_000 });
    await expect(page.getByText(/You were not charged/)).toBeVisible();

    // the auto at the pickup is offered instead
    const nearby = page.getByRole('region', { name: 'Rides nearby now' });
    await expect(nearby.getByRole('button', { name: 'Book Auto' })).toBeVisible();
    await expect(nearby.getByRole('button', { name: 'Book Bike' })).toHaveCount(0);

    // Try again: the booking screen with the same trip and ride type
    await page.getByRole('button', { name: 'Try again' }).click();
    await expect(page).toHaveURL(/\/ride$/);
    await expect(page.getByRole('combobox', { name: 'Pickup' })).toHaveValue('Vellore Fort');
    await expect(page.getByRole('combobox', { name: 'Drop' })).toHaveValue('Katpadi');
    await expect(page.getByRole('radio', { name: /^Bike/ })).toHaveAttribute('aria-checked', 'true');

    // back on the old ride, one tap books the auto, and the auto is offered the new ride
    await page.goto(`/ride/${rideId}`);
    await page.getByRole('region', { name: 'Rides nearby now' }).getByRole('button', { name: 'Book Auto' }).click();
    await page.waitForURL((url) => /^\/ride\/[^/]+$/.test(url.pathname) && !url.pathname.endsWith(rideId));
    const newRideId = new URL(page.url()).pathname.split('/').pop()!;
    await expect.poll(async () => (await request.get('/api/drivers/me/offer', { headers: auth(driver.token) })).status(), { timeout: 30_000 }).toBe(200);

    // leave nothing behind for the next test: the new ride cancelled, the auto offline
    await request.post(`/api/rides/${newRideId}/cancel`, { headers: auth(rider.token), data: { reason: 'e2e done' } });
  } finally {
    clearInterval(heartbeat);
    await request.patch('/api/drivers/me/status', { headers: auth(driver.token), data: { status: 'OFFLINE' } });
  }
});
