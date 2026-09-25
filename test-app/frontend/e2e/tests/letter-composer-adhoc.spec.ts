// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import { test, expect, type Page } from '@playwright/test';
import { openDossier } from '../pages/correspondentie.page';

/**
 * An ad-hoc letter from an open dossier, with **no user task anywhere**.
 *
 * The composer sits on the start form of a process that runs on the case already open, so a case
 * worker picks the letter in the Start dialog and the process only generates what was chosen. That
 * is the shape worth proving in a browser: authorization no longer comes from a task, and the case
 * the letter is composed for comes from the route rather than from a task's business key — see
 * `docs/letter-composer.md` and ADR 0004.
 *
 * Needs a reachable Epistola, since the preview renders a real PDF.
 */

/**
 * Open the ad-hoc letter from the dossier's Start menu.
 *
 * The menu fills in after the dossier loads, and clicking Start toggles it — so wait for the item
 * to be *visible* rather than merely present, or a retry closes the menu again and the click lands
 * on a hidden element.
 */
async function startAdHocLetter(page: Page) {
  const adHoc = page.getByText('Losse brief versturen', { exact: true });
  const startButton = page.getByRole('button', { name: /^Start/ }).first();

  await expect
    .poll(
      async () => {
        if (await adHoc.isVisible().catch(() => false)) {
          return true;
        }
        await startButton.click({ force: true });
        await page.waitForTimeout(1_500);
        return adHoc.isVisible().catch(() => false);
      },
      { timeout: 40_000, message: 'the ad-hoc letter process never appeared in the Start menu' },
    )
    .toBe(true);

  await adHoc.click({ force: true });
}

test.describe('Letter composer — an ad-hoc letter, without a user task', () => {
  test('composes and generates from the dossier itself', async ({ page }) => {
    test.setTimeout(180_000);

    await openDossier(page);
    await startAdHocLetter(page);

    // The composer runs on a start form: no task exists, and the case comes from the open dossier.
    const select = page.getByTestId('epistola-composer-select');
    await select.waitFor({ timeout: 25_000 });
    await select.selectOption('besluit-bezwaar');

    // Still exactly the three fields the case cannot supply — the baseline mapping read this
    // dossier through $doc, which is what proves the case reached the backend at all.
    const inputs = page.getByTestId('epistola-composer-inputs');
    await inputs.locator('[name="data[decisionType]"]').waitFor({ timeout: 30_000 });
    for (const field of ['decisionType', 'decision', 'motivation']) {
      await expect(inputs.locator(`[name="data[${field}]"]`)).toBeVisible();
    }

    await inputs.locator('[name="data[decisionType]"]').fill('gegrond');
    await inputs.locator('[name="data[decision]"]').fill('Het bezwaar is gegrond verklaard.');
    await inputs.locator('[name="data[motivation]"]').fill('Ad-hoc brief, zonder taak.');
    await expect(page.getByTestId('epistola-composer-preview-pdf')).toBeVisible({
      timeout: 60_000,
    });

    await page.getByRole('button', { name: 'Verstuur brief' }).click({ force: true });

    // The dialog closes and no task is left behind: the process generates on its own.
    await expect(page.getByTestId('epistola-composer')).toBeHidden({ timeout: 30_000 });
    const visibleText = async () => (await page.locator('body').innerText()).replace(/\s+/g, ' ');
    await expect
      .poll(visibleText, { timeout: 20_000, message: 'an ad-hoc letter must create no task' })
      .not.toContain('Losse brief versturen Open');
  });
});
