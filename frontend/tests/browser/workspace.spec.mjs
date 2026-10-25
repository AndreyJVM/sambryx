import { test, expect } from './fixtures.mjs';

for (const authType of ['password', 'key']) {
  test(`login sends ${authType} credentials once after CSRF bootstrap`, async ({ page, app }) => {
    app.authenticated = false;
    await page.goto('/ui/login');
    await page.locator('#login-host').fill('files.example.test');
    await page.locator('#login-username').fill('operator');
    if (authType === 'key') {
      await page.getByRole('button', { name: 'SSH-ключ', exact: true }).click();
      await page.locator('textarea').fill('synthetic-key-not-a-real-private-key');
    } else await page.locator('#login-password').fill('synthetic-password');
    await page.getByRole('button', { name: 'Подключиться', exact: true }).click();
    await expect(page).toHaveURL(/\/ui\/shares$/);
    const logins = app.requests.filter(request => request.path === '/api/auth/login');
    expect(logins).toHaveLength(1);
    expect(logins[0].headers['x-xsrf-token']).toBe('browser-fixture');
    expect(logins[0].body).toEqual({ host: 'files.example.test', port: 22, username: 'operator', authType,
      ...(authType === 'key' ? { privateKey: 'synthetic-key-not-a-real-private-key' } : { password: 'synthetic-password' }) });
    expect(app.requests.findIndex(request => request.path === '/api/auth/environment')).toBeLessThan(app.requests.indexOf(logins[0]));
  });
}

test('changed host key blocks login and shows an actionable warning', async ({ page, app }) => {
  app.authenticated = false;
  app.loginFailure = true;
  await page.goto('/ui/login');
  await page.locator('#login-host').fill('files.example.test');
  await page.locator('#login-username').fill('operator');
  await page.locator('#login-password').fill('synthetic-password');
  await page.getByRole('button', { name: 'Подключиться', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('возможна подмена сервера');
  await expect(page.getByRole('alert')).toContainText('SHA256:synthetic-browser-fixture');
  await expect(page).toHaveURL(/\/ui\/login$/);
  expect(app.requests.filter(request => request.path === '/api/auth/login')).toHaveLength(1);
});

test('all workspace sections are reachable through visible navigation', async ({ page }) => {
  await page.goto('/ui/dashboard');
  const navigation = page.getByRole('navigation', { name: 'Main navigation' });
  for (const route of ['shares', 'users', 'groups', 'config', 'ad', 'fstec', 'dashboard']) {
    await navigation.locator(`a[href="/ui/${route}"]`).click();
    await expect(page).toHaveURL(new RegExp(`/ui/${route}$`));
    await expect(page.locator('main h1')).toBeVisible();
    await expect(navigation.locator('[aria-current="page"]')).toHaveAttribute('href', `/ui/${route}`);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  }
});

test('theme preference survives reload', async ({ page }, testInfo) => {
  await page.goto('/ui/dashboard');
  const startsDark = testInfo.project.use.colorScheme === 'dark';
  await page.getByRole('button', { name: startsDark ? 'Light mode' : 'Dark mode', exact: true }).click();
  await expect(page.locator('html')).toHaveClass(startsDark ? /^$/ : /dark/);
  await page.reload();
  await expect(page.locator('html')).toHaveClass(startsDark ? /^$/ : /dark/);
});

test('share search distinguishes no matches from an empty server', async ({ page, app }) => {
  await page.goto('/ui/shares');
  await expect(page.getByText('team', { exact: true })).toBeVisible();
  const search = page.getByRole('searchbox');
  for (const query of ['TEAM', '/srv/samba/team', 'Project documents']) {
    await search.fill(query);
    await expect(page.getByText('team', { exact: true })).toBeVisible();
    await expect(page.getByText('archive', { exact: true })).toHaveCount(0);
  }
  await search.fill('not-found');
  await expect(page.getByRole('heading', { name: 'No matching shares' })).toBeVisible();
  await page.getByRole('button', { name: 'Clear search', exact: true }).click();
  app.shares = [];
  await page.locator('main').getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'No shares available' })).toBeVisible();
});

test('failed reads retain stale shares and do not report an empty server', async ({ page, app }) => {
  await page.goto('/ui/shares');
  await expect(page.getByText('team', { exact: true })).toBeVisible();
  app.failShares = true;
  await page.locator('main').getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Showing the previous list');
  await expect(page.getByText('team', { exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('alert')).toContainText('Could not load shares');
  await expect(page.getByRole('heading', { name: 'No shares available' })).toHaveCount(0);
  app.failShares = false;
  await page.locator('main').getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect(page.getByText('team', { exact: true })).toBeVisible();
  await expect(page.getByRole('alert')).toHaveCount(0);
});

test('nested dialogs confine Tab and restore focus after Escape', async ({ page }) => {
  await page.goto('/ui/shares');
  const trigger = page.getByRole('button', { name: 'New Share', exact: true });
  await trigger.click();
  const editor = page.getByRole('dialog', { name: 'New Share', exact: true });
  await editor.getByRole('button', { name: 'Save', exact: true }).focus();
  await page.keyboard.press('Tab');
  await expect(editor.getByRole('button', { name: 'Close', exact: true })).toBeFocused();
  const browse = editor.getByRole('button', { name: 'Browse...', exact: true });
  await browse.click();
  await expect(page.getByRole('dialog')).toHaveCount(2);
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).toHaveCount(1);
  await expect(browse).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(trigger).toBeFocused();
});

test('canceling share deletion and service stop sends no mutation', async ({ page, app }) => {
  await page.goto('/ui/shares');
  await page.getByRole('button', { name: 'Delete: archive', exact: true }).click();
  await page.getByRole('alertdialog').getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(page.getByText('archive', { exact: true })).toBeVisible();
  await page.goto('/ui/dashboard');
  await page.locator('main').getByRole('button', { name: 'Stop', exact: true }).click();
  await expect(page.getByRole('alertdialog')).toContainText('Stop Samba?');
  await page.keyboard.press('Escape');
  await expect(page.getByRole('alertdialog')).toHaveCount(0);
  expect(app.requests.filter(request => request.method !== 'GET')).toEqual([]);
});

test('canceling account deletion and member removal sends no mutation', async ({ page, app }) => {
  await page.goto('/ui/users');
  await page.getByRole('button', { name: 'Delete: alice', exact: true }).click();
  await page.getByRole('alertdialog').getByRole('button', { name: 'Cancel', exact: true }).click();
  await page.goto('/ui/groups');
  await page.getByRole('button', { name: 'Remove: alice (engineering)', exact: true }).click();
  await page.getByRole('alertdialog').getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(page.getByText('alice', { exact: true })).toBeVisible();
  expect(app.requests.filter(request => request.method !== 'GET')).toEqual([]);
});

test('unavailable dashboard disables service actions', async ({ page, app }) => {
  await page.goto('/ui/dashboard');
  await expect(page.locator('main').getByRole('button', { name: 'Stop', exact: true })).toBeEnabled();
  app.failDashboard = true;
  await page.locator('main').getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  await expect(page.locator('main').getByRole('button', { name: 'Stop', exact: true })).toBeDisabled();
  await expect(page.locator('main').getByRole('button', { name: 'Restart', exact: true })).toBeDisabled();
});
