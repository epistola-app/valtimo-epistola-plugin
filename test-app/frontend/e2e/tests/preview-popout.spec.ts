// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import { expect, test, type Locator, type Page } from '@playwright/test';

/**
 * The document preview and the document component pop out of their form, like the composer's.
 *
 * Walked on the `permit` demo, which has both: a document preview on the start form, and the
 * generated letter (`epistola-document`) on the review task. What a unit test cannot show is that
 * a panel moved out of a real Valtimo dialog leaves the dialog working, docks back into it, and goes
 * when the dialog does.
 *
 * Needs a reachable Epistola, since both render a real PDF.
 */
async function popsOutAndDocks(page: Page, panel: Locator, popOut: Locator): Promise<void> {
  await popOut.click();
  expect(await panel.evaluate((el) => el.parentElement === document.body)).toBe(true);
  await popOut.click();
  expect(await panel.evaluate((el) => el.parentElement !== document.body)).toBe(true);
}

test.describe('Previews outside the composer — pop out', () => {
  test('the start-form preview and the generated letter both pop out and dock', async ({
    page,
  }) => {
    test.setTimeout(180_000);
    const visibleText = async () => (await page.locator('body').innerText()).replace(/\s+/g, ' ');

    await page.goto('/cases/permit');
    await page.getByRole('navigation', { name: /Side navigation/i }).waitFor({ timeout: 20_000 });
    await page.getByRole('button', { name: 'Creëer Nieuw Dossier' }).first().click();

    // A valid BSN, so the letter renders and the process goes straight on to review.
    await page.locator('input[name="data[applicant.bsn]"]').fill('123456782');
    for (const field of await page
      .locator('.formio-component-activities')
      .locator('input:visible, textarea:visible')
      .all()) {
      if (!(await field.inputValue())) {
        await field.fill((await field.getAttribute('type')) === 'number' ? '5000' : 'Dakkapel');
      }
    }

    const preview = page.getByTestId('epistola-preview-runtime');
    await expect(preview).toBeVisible({ timeout: 30_000 });
    await popsOutAndDocks(page, preview, page.getByTestId('epistola-preview-popout'));

    // Left floating when the form is submitted: it goes with the form rather than staying behind.
    await page.getByTestId('epistola-preview-popout').click();
    await page.getByRole('button', { name: 'Indienen' }).click();
    await expect(page).toHaveURL(/\/cases\/permit\/document\/[0-9a-f-]{36}/, { timeout: 30_000 });
    await expect(page.getByTestId('epistola-preview-runtime')).toHaveCount(0, { timeout: 10_000 });

    await expect
      .poll(
        async () => {
          await page.reload();
          await page.waitForTimeout(2_000);
          return visibleText();
        },
        { timeout: 90_000, intervals: [3_000], message: 'the review task never appeared' },
      )
      .toContain('Controleer Bevestigingsbrief');
    await page.getByText('Controleer Bevestigingsbrief').first().click();

    const letter = page.getByTestId('epistola-document-inline-view');
    await expect(letter).toBeVisible({ timeout: 30_000 });
    await popsOutAndDocks(page, letter, page.getByTestId('epistola-document-popout'));
  });
});
