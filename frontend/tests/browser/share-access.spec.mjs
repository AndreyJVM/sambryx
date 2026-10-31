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

for (const language of ['ru', 'en']) {
  test.describe(`share action layout ${language}`, () => {
    test.use({ appLanguage: language });
    test('actions fit their cells across viewport widths', async ({ page }, testInfo) => {
      for (const width of [1440, 1200, 1101, 360, 320]) {
        await page.setViewportSize({ width, height: 1000 });
        await page.goto('/ui/shares');
        const label = language === 'ru' ? 'Проверить доступ' : 'Check access';
        const trigger = page.getByRole('button', { name: `${label}: team`, exact: true });
        await expect(trigger).toBeVisible();
        const geometry = await trigger.locator('..').locator('..').evaluate(cell => {
          const bounds = cell.getBoundingClientRect();
          return [...cell.querySelectorAll('button')].map(button => {
            const rect = button.getBoundingClientRect();
            const range = document.createRange();
            range.selectNodeContents(button);
            const content = range.getBoundingClientRect();
            return { x: rect.x, y: rect.y, right: rect.right, bottom: rect.bottom,
              width: rect.width, height: rect.height,
              contained: rect.left >= bounds.left && rect.right <= bounds.right,
              contentFits: content.left >= rect.left && content.right <= rect.right };
          });
        });
        expect(geometry).toHaveLength(3);
        for (const button of geometry) {
          expect(button.contained).toBe(true);
          expect(button.contentFits).toBe(true);
          expect(button.width).toBeGreaterThanOrEqual(40);
          expect(button.height).toBeGreaterThanOrEqual(40);
        }
        for (let i = 0; i < geometry.length; i++) {
          for (const other of geometry.slice(i + 1)) {
            const button = geometry[i];
            expect(button.right <= other.x || other.right <= button.x || button.bottom <= other.y || other.bottom <= button.y).toBe(true);
          }
        }
        await page.screenshot({ path: testInfo.outputPath(`${language}-${width}.png`), fullPage: true });
        await trigger.click();
        await expect(page.getByRole('dialog')).toBeVisible();
        await page.keyboard.press('Escape');
        await expect(trigger).toBeFocused();
      }
    });
  });
}
