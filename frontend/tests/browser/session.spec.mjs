import { test, expect } from './fixtures.mjs';

test('idle warning appears without renewing background activity and continue renews once', async ({ page, app }, testInfo) => {
  app.sessionRemaining = 61_000;
  await page.clock.install();
  await page.goto('/ui/shares');
  await expect(page.locator('main h1')).toBeVisible();
  await page.clock.fastForward(2000);
  const warning = page.getByRole('dialog', { name: 'Session ending soon' });
  await expect(warning).toBeVisible();
  expect(app.requests.filter(r => r.path === '/api/auth/activity')).toHaveLength(0);
  await page.screenshot({ path: testInfo.outputPath('session-warning.png') });
  await warning.getByRole('button', { name: 'Continue work' }).click();
  await expect(warning).toBeHidden();
  expect(app.requests.filter(r => r.path === '/api/auth/activity')).toHaveLength(1);
});

test('expired renewal redirects without reconnect or retry', async ({ page, app }) => {
  app.sessionRemaining = 50_000;
  app.renewalDenied = true;
  await page.goto('/ui/shares');
  await page.getByRole('button', { name: 'Continue work' }).click();
  await expect(page).toHaveURL(/\/ui\/login$/);
  expect(app.requests.filter(r => r.path === '/api/auth/activity')).toHaveLength(1);
  expect(app.requests.filter(r => r.path === '/api/auth/login')).toHaveLength(0);
});

test('device sleep past deadline checks server without renewing', async ({ page, app }) => {
  app.sessionRemaining = 61_000;
  await page.clock.install();
  await page.goto('/ui/shares');
  await expect(page.locator('main h1')).toBeVisible();
  app.authenticated = false;
  await page.clock.fastForward(62_000);
  await expect(page).toHaveURL(/\/ui\/login$/);
  expect(app.requests.filter(r => r.path === '/api/auth/activity')).toHaveLength(0);
  expect(app.requests.filter(r => r.path === '/api/auth/login')).toHaveLength(0);
});

test('renewal and logout are shared across tabs through server checks', async ({ context, page, app }) => {
  app.sessionRemaining = 50_000;
  await page.goto('/ui/shares');
  const second = await context.newPage();
  await second.goto('/ui/shares');
  const firstWarning = page.getByRole('dialog', { name: 'Session ending soon' });
  await expect(firstWarning).toBeVisible();
  await second.getByRole('button', { name: 'Continue work' }).click();
  await expect(firstWarning).toBeHidden();
  expect(app.requests.filter(r => r.path === '/api/auth/activity')).toHaveLength(1);
  await second.getByRole('button', { name: 'Sign Out', exact: true }).click();
  await expect(second).toHaveURL(/\/ui\/login$/);
  await expect(page).toHaveURL(/\/ui\/login$/);
  expect(app.requests.filter(r => r.path === '/api/auth/login')).toHaveLength(0);
  await second.close();
});

test('Russian warning remains usable on both themes and screen sizes', async ({ page, app }, testInfo) => {
  app.sessionRemaining = 50_000;
  await page.addInitScript(() => localStorage.setItem('samba_lang', 'ru'));
  await page.goto('/ui/shares');
  const warning = page.getByRole('dialog', { name: 'Сессия скоро завершится' });
  await expect(warning).toBeVisible();
  await expect(warning.getByRole('button', { name: 'Продолжить работу' })).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('session-warning-ru.png') });
});

test('automatic initial route and mouse movement never renew activity', async ({ page, app }) => {
  await page.goto('/ui/');
  await expect(page).toHaveURL(/\/ui\/shares$/);
  await expect.poll(() => app.requests.filter(r => r.path === '/api/auth/session').length).toBeGreaterThan(0);
  await page.mouse.move(20, 20);
  expect(app.requests.filter(r => r.path === '/api/auth/activity')).toHaveLength(0);
});
