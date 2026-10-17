import { expect, test, type APIRequestContext } from '@playwright/test';

/**
 * The 4-step flow in a browser: a rider signs in, books a ride and watches it to the end. The
 * driver is played by this test over the same API the driver screen uses (a human driver needs a
 * second browser; the simulator needs Python).
 */

const PASSWORD = 'E2e-only-password-1';
const PICKUP = { lat: 13.0827, lng: 80.2707 };

async function register(request: APIRequestContext, role: 'RIDER' | 'DRIVER') {
  const email = `e2e-${role.toLowerCase()}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@test.rydex.dev`;
  const data = { name: `E2E ${role === 'RIDER' ? 'Rider' : 'Driver'}`, email, password: PASSWORD, role };
  let res = await request.post('/api/auth/register', { data });
  // sign-ups share a 20-a-minute budget per IP with the smoke test that runs just before in CI
  for (let attempt = 0; res.status() === 429 && attempt < 2; attempt++) {
    await new Promise((resolve) => setTimeout(resolve, (Number(res.headers()['retry-after']) || 30) * 1000 + 500));
    res = await request.post('/api/auth/register', { data });
  }
  expect(res.status(), await res.text()).toBe(201);
  const body = await res.json();
  return { email, token: body.accessToken as string };
}

const auth = (token: string) => ({ Authorization: `Bearer ${token}` });

test('a rider books a ride, follows the driver live and sees the fare', async ({ page, request }) => {
  // the driver: a fresh ECONOMY car, online right at the pickup so it is the nearest
  const driver = await register(request, 'DRIVER');
  const plate = `E2E ${Date.now() % 100000}`;
  expect((await request.put('/api/drivers/me', {
    headers: auth(driver.token),
    data: { vehicleType: 'ECONOMY', vehicleNumber: plate, vehicleModel: 'Tata Tiago' },
  })).ok()).toBe(true);
  expect((await request.patch('/api/drivers/me/status', {
    headers: auth(driver.token),
    data: { status: 'AVAILABLE', ...PICKUP },
  })).ok()).toBe(true);

  // the rider signs in and books
  const rider = await register(request, 'RIDER');
  await page.goto('/login');
  await page.getByLabel('Email').fill(rider.email);
  await page.getByLabel('Password', { exact: true }).fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: 'Where to?' })).toBeVisible();
  await expect(page.getByRole('combobox', { name: 'Pickup' })).not.toHaveValue('');

  // the free car waiting at the pickup shows on the rider's map (living city, ADR 0016; bikes, autos and cabs count as rides, ADR 0018)
  await expect(page.getByRole('status').filter({ hasText: /rides? nearby · closest \d+ min/ })).toBeVisible({ timeout: 15_000 });
  await expect(page.getByRole('img', { name: 'Economy nearby' }).first()).toBeAttached();

  // drop: tap the map south-east of the pickup, once the map is up (the pickup pin is on it)
  await expect(page.locator('.rx-pin-pickup')).toBeVisible();
  const map = page.locator('.maplibregl-canvas');
  const box = (await map.boundingBox())!;
  await page.getByRole('combobox', { name: 'Drop' }).focus();
  await map.click({ position: { x: box.width * 0.85, y: box.height * 0.85 } });
  await expect(page.getByRole('radio', { name: /Economy/ })).toBeVisible();
  await expect(page.getByRole('status', { name: /Fare, fixed upfront/ })).toBeVisible();
  await page.getByRole('button', { name: 'Book Economy' }).click();

  await expect(page).toHaveURL(/\/ride\/[0-9a-f-]{36}$/);
  const rideId = page.url().split('/').pop()!;
  await expect(page.getByRole('status', { name: /Finding your driver/ })).toBeVisible();

  // the offer reaches our driver; accept it
  await expect.poll(async () => {
    const res = await request.get('/api/drivers/me/offer', { headers: auth(driver.token) });
    return res.status() === 200 ? (await res.json()).rideId : null;
  }, { timeout: 30_000 }).toBe(rideId);
  expect((await request.post(`/api/drivers/me/offers/${rideId}/accept`, { headers: auth(driver.token) })).ok()).toBe(true);

  // the rider sees the driver, the plate, the PIN, and the car coming
  await expect(page.getByRole('status', { name: /Driver (arriving|on the way)/ })).toBeVisible();
  await expect(page.getByText(plate)).toBeVisible();
  const pinLabel = await page.getByLabel(/^Ride PIN/).getAttribute('aria-label');
  const pin = pinLabel!.replace(/\D/g, '');
  expect(pin).toHaveLength(4);

  const near = { lat: PICKUP.lat + 0.001, lng: PICKUP.lng, heading: 180, speed: 8 };
  expect((await request.post('/api/drivers/me/location', { headers: auth(driver.token), data: near })).ok()).toBe(true);

  // arrive, start with the rider's PIN, complete
  expect((await request.post(`/api/rides/${rideId}/arrived`, { headers: auth(driver.token) })).ok()).toBe(true);
  await expect(page.getByRole('status', { name: /Your driver is here/ })).toBeVisible();
  expect((await request.post(`/api/rides/${rideId}/start`, { headers: auth(driver.token), data: { otp: pin } })).ok()).toBe(true);
  await expect(page.getByRole('status', { name: /On the way/ })).toBeVisible();

  // realistic mode: 3D buildings, the 3D car and a camera that follows it, then back to 2D
  await page.getByRole('button', { name: 'Realistic 3D' }).click();
  await expect(page.getByRole('button', { name: 'Exit realistic mode' })).toBeVisible();
  await expect(page.getByRole('status', { name: /On the way/ })).toBeVisible();
  await page.getByRole('button', { name: 'Exit realistic mode' }).click();
  await expect(page.getByRole('button', { name: 'Realistic 3D' })).toBeVisible();
  expect((await request.post(`/api/rides/${rideId}/complete`, { headers: auth(driver.token) })).ok()).toBe(true);

  await expect(page.getByRole('status', { name: /You have arrived: ₹\d+/ })).toBeVisible();
  // mock provider: paid at once; Razorpay test mode: the rider is asked to pay
  await expect(page.getByText('Paid', { exact: true }).or(page.getByRole('button', { name: /^Pay ₹/ }))).toBeVisible({ timeout: 30_000 });

  // and the ride is in the rider's history
  await page.getByRole('link', { name: 'Your rides' }).click();
  await expect(page.getByText('Completed')).toBeVisible();
});
