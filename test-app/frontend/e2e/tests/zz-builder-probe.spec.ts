// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

// Scratch probe — not committed. Learns how the Form.io builder addresses a composer's settings.
import { test, expect } from '@playwright/test';

test('probe: open a form in the builder', async ({ page }) => {
  test.setTimeout(120_000);
  await page.goto('/form-management');
  await page.waitForLoadState('domcontentloaded');
  await page.waitForTimeout(3_000);

  const rows = page.locator('table tbody tr');
  console.log('PROBE forms listed:', await rows.count());
  const names = await page.locator('table tbody tr td').allInnerTexts();
  console.log('PROBE first cells:', JSON.stringify(names.slice(0, 12)));

  const kiesBrief = rows.filter({ hasText: 'kies-brief' }).first();
  if (await kiesBrief.count()) {
    await kiesBrief.click({ force: true });
    await page.waitForTimeout(4_000);
    console.log('PROBE url after opening:', page.url());
    const components = await page
      .locator('[class*="formcomponent"], .formarea .formio-component')
      .count();
    console.log('PROBE builder components on canvas:', components);
    const text = (await page.locator('body').innerText()).replace(/\s+/g, ' ');
    console.log(
      'PROBE page mentions composer:',
      text.includes('brief') || text.includes('composer'),
    );
  }
  expect(true).toBe(true);
});
