import { chromium } from '@playwright/test';
const browser = await chromium.launch();
const context = await browser.newContext({
  storageState: 'e2e/.auth/user.json',
  viewport: { width: 1440, height: 900 },
});
const page = await context.newPage();
page.on('console', (m) => {
  if (m.type() === 'error' && !/NG0100|toLowerCase|CORS|ERR_FAILED|undefined|404/.test(m.text()))
    console.log('ERR', m.text().slice(0, 160));
});
page.on('response', (r) => {
  if (r.url().includes('/api/') && r.status() >= 400)
    console.log('HTTP', r.status(), r.url().replace('http://localhost:8080', '').slice(0, 90));
});

await page.goto('http://localhost:4200/cases/correspondentie');
await page.waitForTimeout(5000);
await page.locator('table tbody tr').first().click({ force: true });
await page.waitForTimeout(5000);

const adHoc = page.getByText('Losse brief versturen', { exact: true });
const start = page.getByRole('button', { name: /^Start/ }).first();
for (let i = 0; i < 4; i++) {
  const visible = await adHoc.isVisible().catch(() => false);
  console.log(`attempt ${i}: visible=${visible} count=${await adHoc.count()}`);
  if (visible) break;
  await start.click({ force: true });
  await page.waitForTimeout(1500);
}
await adHoc.click({ force: true });
await page.waitForTimeout(4000);
console.log(
  'AFTER CLICK: composer selects =',
  await page.getByTestId('epistola-composer-select').count(),
);
console.log('TEXT:', (await page.locator('body').innerText()).replace(/\s+/g, ' ').slice(0, 220));
await browser.close();
