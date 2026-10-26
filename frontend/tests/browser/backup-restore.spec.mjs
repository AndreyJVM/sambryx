import { test, expect } from './fixtures.mjs';

const selected = 'smb.conf.backup_20261024_123456';
const other = 'smb.conf.backup_20261023_123456';
const mutations = app => app.requests.filter(request => request.method !== 'GET' && request.path !== '/api/auth/activity');

async function openBackups(page, app) {
  app.backups = [selected, other].map(filename => ({ filename, createdAt: '2026-10-24 12:34:56', size: '2 KB' }));
  await page.goto('/ui/config');
  await expect(page.getByRole('button', { name: `Restore: ${selected}`, exact: true })).toBeVisible();
}

async function confirmRestore(page) {
  await page.getByRole('button', { name: `Restore: ${selected}`, exact: true }).click();
  await page.getByRole('alertdialog').getByRole('button', { name: 'Restore', exact: true }).click();
}

test('restore confirmation identifies the backup and consequences; Cancel and Escape send no mutation', async ({ page, app }, testInfo) => {
  await openBackups(page, app);
  for (const cancel of ['button', 'escape']) {
    await page.getByRole('button', { name: `Restore: ${selected}`, exact: true }).click();
    const dialog = page.getByRole('alertdialog');
    await expect(dialog).toContainText(selected);
    await expect(dialog).not.toContainText(other);
    await expect(dialog).toContainText('replace smb.conf');
    await expect(dialog).toContainText('restart smbd');
    await expect(dialog).toContainText('Unsaved form changes');
    await page.screenshot({ path: testInfo.outputPath(`confirmation-${cancel}.png`) });
    if (cancel === 'button') await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
    else await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
    await expect(page.getByRole('button', { name: `Restore: ${selected}`, exact: true })).toBeFocused();
  }
  expect(mutations(app)).toEqual([]);
});

test('pending restore prevents duplicate mutations and success refreshes configuration and backups', async ({ page, app }, testInfo) => {
  let release;
  app.restore = { filename: selected, wait: new Promise(resolve => { release = resolve; }) };
  await openBackups(page, app);
  await confirmRestore(page);
  try {
    const status = page.locator('main').getByRole('status');
    await expect(status).toContainText(`Restoring backup: ${selected}`);
    await expect(status).toBeInViewport();
    for (const filename of [selected, other]) await expect(page.getByRole('button', { name: `Restore: ${filename}`, exact: true })).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Apply Config', exact: true })).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Reset Defaults', exact: true })).toBeDisabled();
    await page.getByRole('button', { name: `Restore: ${other}`, exact: true }).evaluate(button => button.click());
    await page.screenshot({ path: testInfo.outputPath('pending.png'), fullPage: true });
    expect(mutations(app).map(({ method, path }) => ({ method, path }))).toEqual([{ method: 'POST', path: `/api/config/backups/${selected}/restore` }]);
  } finally { release(); }
  await expect(page.getByRole('status').filter({ hasText: 'Backup restored' })).toContainText(selected);
  await expect(page.getByRole('status').filter({ hasText: 'Backup restored' })).toBeInViewport();
  await expect(page.locator('#config-workgroup')).toHaveValue('RESTORED');
  await expect(page.getByText('smb.conf.backup_20261025_140000', { exact: true })).toBeVisible();
  expect(app.requests.filter(request => request.path === '/api/config/global')).toHaveLength(2);
  expect(app.requests.filter(request => request.path === '/api/config/backups')).toHaveLength(2);
  expect(mutations(app)).toHaveLength(1);
  await page.screenshot({ path: testInfo.outputPath('success.png'), fullPage: true });
});

for (const failure of ['validation rejection', 'restart failure', 'lost response']) {
  test(`${failure} shows a safe uncertain result without success or retry`, async ({ page, app }, testInfo) => {
    app.restore = { filename: selected, status: 500, abort: failure === 'lost response', message: failure === 'validation rejection'
      ? 'Samba command failed: testparm rejected synthetic-sensitive-output' : 'Samba command failed: restart failed synthetic-sensitive-output' };
    await openBackups(page, app);
    await page.locator('#config-workgroup').fill('UNSAVED');
    await confirmRestore(page);
    const alert = page.locator('main').getByRole('alert');
    await expect(alert).toContainText('Restore not confirmed');
    await expect(alert).toContainText(selected);
    await expect(alert).toBeInViewport();
    await expect(alert).toContainText('Check the server configuration and service status before trying again');
    await expect(page.getByText(/synthetic-sensitive-output/)).toHaveCount(0);
    await expect(page.getByRole('status').filter({ hasText: 'Backup restored' })).toHaveCount(0);
    await expect(page.locator('#config-workgroup')).toHaveValue('UNSAVED');
    await expect(page.getByRole('button', { name: `Restore: ${selected}`, exact: true })).toBeEnabled();
    expect(mutations(app)).toHaveLength(1);
    expect(app.requests.filter(request => request.path === '/api/config/global')).toHaveLength(1);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath('error.png'), fullPage: true });
  });
}

test('successful restore with failed refresh warns about stale form data', async ({ page, app }) => {
  app.restore = { filename: selected, failRefresh: true };
  await openBackups(page, app);
  await confirmRestore(page);
  await expect(page.getByRole('status').filter({ hasText: 'Backup restored' })).toContainText(selected);
  await expect(page.getByRole('status').filter({ hasText: 'Backup restored' })).toBeInViewport();
  await expect(page.locator('main').getByRole('alert')).toContainText('displayed configuration may be out of date');
  await expect(page.getByText('Restore not confirmed', { exact: true })).toHaveCount(0);
  await expect(page.getByText(/synthetic-sensitive-refresh-output/)).toHaveCount(0);
  await expect(page.locator('#config-workgroup')).toHaveValue('TEAM');
  expect(mutations(app)).toHaveLength(1);
});

test('Russian restore messages identify the selected backup and explain the uncertain failure', async ({ page, app }, testInfo) => {
  app.restore = { filename: selected, status: 500, message: 'synthetic-sensitive-output' };
  await openBackups(page, app);
  await page.getByRole('button', { name: /^en$/i }).click();
  await page.getByRole('button', { name: `Восстановить: ${selected}`, exact: true }).click();
  const dialog = page.getByRole('alertdialog');
  await expect(dialog).toContainText(selected);
  await expect(dialog).toContainText('заменит smb.conf и перезапустит smbd');
  await page.screenshot({ path: testInfo.outputPath('confirmation-ru.png') });
  await dialog.getByRole('button', { name: 'Восстановить', exact: true }).click();
  await expect(page.locator('main').getByRole('alert')).toContainText('Восстановление не подтверждено');
  await expect(page.locator('main').getByRole('alert')).toContainText(selected);
  await expect(page.getByText('synthetic-sensitive-output', { exact: true })).toHaveCount(0);
  expect(mutations(app)).toHaveLength(1);
});
