import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests/browser',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: process.env.CI ? 1 : 2,
  timeout: 20_000,
  globalTimeout: 5 * 60_000,
  expect: { timeout: 5_000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: 'http://127.0.0.1:4177',
    browserName: 'chromium',
    channel: process.env.PLAYWRIGHT_CHANNEL || undefined,
    locale: 'en-US',
    serviceWorkers: 'block',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: ['light', 'dark'].flatMap(colorScheme => [
    { name: `desktop-${colorScheme}`, use: { colorScheme, viewport: { width: 1440, height: 1000 } } },
    { name: `mobile-${colorScheme}`, use: { colorScheme, viewport: { width: 320, height: 844 }, isMobile: true, hasTouch: true } },
  ]),
  webServer: {
    command: 'npm run preview -- --host 127.0.0.1 --port 4177 --strictPort',
    url: 'http://127.0.0.1:4177/ui/',
    reuseExistingServer: false,
    timeout: 30_000,
  },
});
