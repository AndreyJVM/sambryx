import { test, expect } from './fixtures.mjs';

test('share access check requires explicit identity and shows partial read-only findings', async ({ page, app }, testInfo) => {
  let finish;
  const pending = new Promise(resolve => { finish = resolve; });
  const requests = [];
  await page.route('**/api/shares/*/access-check?*', async route => {
    requests.push(route.request());
    await pending;
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true, data: {
      share: 'team', username: 'bob', findings: [
        { code: 'UNAVAILABLE', status: 'BLOCKING', evidence: 'unavailable', nextAction: 'reviewConfig' },
        { code: 'LIMITS', status: 'UNKNOWN', evidence: 'limits', nextAction: 'verifyClient' },
      ],
    } }) });
  });
  await page.goto('/ui/shares');
  const trigger = page.getByRole('button', { name: 'Check access: team', exact: true });
  await trigger.click();
  const dialog = page.getByRole('dialog');
  const check = dialog.getByRole('button', { name: 'Run check', exact: true });
  await expect(check).toBeDisabled();
  await dialog.getByLabel('Local username').fill('bob');
  await check.click();
  await expect(dialog.getByRole('status')).toHaveText('Checking…');
  await expect(check).toBeDisabled();
  finish();
  await expect(dialog.getByText('The checked Samba configuration marks this share as unavailable.')).toBeVisible();
  await expect(dialog.getByText('Not checked', { exact: true })).toBeVisible();
  await expect(dialog.getByText(/SMB authentication/)).toBeVisible();
  expect(await dialog.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath('access-report.png'), fullPage: true });
  expect(requests).toHaveLength(1);
  expect(new URL(requests[0].url()).searchParams.get('username')).toBe('bob');
  expect(new URL(requests[0].url()).pathname).toBe('/api/shares/team/access-check');
  expect(requests[0].method()).toBe('GET');
  expect(app.requests.filter(r => !['GET', 'HEAD'].includes(r.method) && !(r.method === 'POST' && r.path === '/api/auth/activity'))).toEqual([]);
  await page.keyboard.press('Escape');
  await expect(dialog).toHaveCount(0);
  await expect(trigger).toBeFocused();
});

test('share access error allows explicit retry without mutating requests', async ({ page, app }) => {
  let calls = 0;
  await page.route('**/api/shares/*/access-check?*', route => {
    calls++;
    return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ success: false, message: 'Fixture unavailable' }) });
  });
  await page.goto('/ui/shares');
  await page.getByRole('button', { name: 'Check access: archive', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('Local username').fill('alice');
  await dialog.getByRole('button', { name: 'Run check', exact: true }).click();
  await expect(dialog.getByRole('alert')).toHaveText('Unable to check access. Try again.');
  expect(calls).toBe(1);
  await dialog.getByRole('button', { name: 'Run check', exact: true }).click();
  await expect.poll(() => calls).toBe(2);
  expect(app.requests.filter(r => !['GET', 'HEAD'].includes(r.method) && !(r.method === 'POST' && r.path === '/api/auth/activity'))).toEqual([]);
});
