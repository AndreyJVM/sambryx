import { chromium } from '@playwright/test';
import { mkdir, readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';

const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_CHANNEL || undefined });
try {
  await mkdir('branding-previews', { recursive: true });
  for (const width of [320, 1440]) {
    for (const theme of ['light', 'dark']) {
      const page = await browser.newPage({ viewport: { width, height: 400 }, colorScheme: theme });
      await page.route('**/*', route => route.abort());
      const names = [`logo-full-${theme}`, 'logo-mark', 'favicon'];
      const images = await Promise.all(names.map(async name => {
        const data = await readFile(new URL(`../public/sambryx/${name}.svg`, import.meta.url));
        return `<img alt="${name}" src="data:image/svg+xml;base64,${data.toString('base64')}" style="max-width:100%;height:64px">`;
      }));
      await page.setContent(`<body style="margin:16px;background:${theme === 'dark' ? '#14181b' : '#fff'}">${images.map(image => `<div>${image}</div>`).join('')}</body>`);
      await page.locator('img').evaluateAll(async images => Promise.all(images.map(image => image.decode())));
      assert.equal(await page.locator('img').count(), names.length);
      for (const image of await page.locator('img').all()) {
        const box = await image.boundingBox();
        assert.ok(box && box.width > 0 && box.height > 0 && box.x >= 0 && box.x + box.width <= width,
          'Branding image must load and fit the viewport');
      }
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
        'Branding preview must not overflow');
      await page.screenshot({ path: `branding-previews/${theme}-${width}.png` });
      await page.close();
    }
  }
} finally {
  await browser.close();
}
