import { defineConfig, devices } from '@playwright/test';

/**
 * Rides in a real browser against the running backend (the four services on 8081-8084; see
 * README). Reuses a running `npm run dev`, or starts one.
 */
export default defineConfig({
  testDir: './e2e',
  // one at a time: each test is a whole ride, and two software-rendered (SwiftShader) browsers next to
  // four services and Kafka starved an 8 GB laptop until Kafka fenced its broker
  workers: 1,
  timeout: 240_000,
  expect: { timeout: 20_000 },
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    geolocation: { latitude: 13.0827, longitude: 80.2707 },
    permissions: ['geolocation'],
  },
  // the maps are WebGL: headless Chromium without a GPU draws them with SwiftShader
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], launchOptions: { args: ['--enable-unsafe-swiftshader', '--use-angle=swiftshader'] } } }],
  webServer: process.env.E2E_BASE_URL ? undefined : { command: 'npm run dev', url: 'http://localhost:5173', reuseExistingServer: true, timeout: 60_000 },
});
