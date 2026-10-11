// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import { expect, test } from '@playwright/test';

/**
 * A composer on the form that **creates** the case.
 *
 * The fourth and last shape a composer can appear in, and the one with no case to read from: the
 * dossier does not exist until this submission commits. So the letter's data comes entirely from
 * the employee, the composer has to be told which process it starts (nothing can derive it), and
 * write-back lands on a case created by the very submission that composed the letter.
 */
test.describe('Letter composer — on the form that creates the case', () => {
  test('composes a letter, and its values reach the new case', async ({ page }) => {
    test.setTimeout(180_000);

    await page.goto('/cases/correspondentie');
    await page.getByRole('navigation', { name: /Side navigation/i }).waitFor({ timeout: 20_000 });
    await page
      .getByRole('button', { name: /Creëer Nieuw Dossier|Start Dossier/ })
      .first()
      .click();

    // Two processes can create this case, so Valtimo asks which. Carbon renders each tile twice,
    // so the visible one has to be picked rather than the first match.
    const tile = page.locator('cds-clickable-tile').filter({ hasText: 'Nieuw dossier met brief' });
    const tiles = await tile.count();
    for (let i = 0; i < tiles; i++) {
      if (
        await tile
          .nth(i)
          .isVisible()
          .catch(() => false)
      ) {
        await tile.nth(i).click();
        break;
      }
    }

    // The composer works here only because the form names the process it starts: there is neither
    // a task nor a case to derive it from. Without it the component stays inert on purpose.
    const composer = page.getByTestId('epistola-composer');
    await expect(composer).toBeVisible({ timeout: 30_000 });
    await expect(page.getByTestId('epistola-composer-no-task')).toBeHidden();

    await page.getByTestId('epistola-composer-select').selectOption('besluit-bezwaar');
    const inputs = page.getByTestId('epistola-composer-inputs');
    await expect(inputs).toBeVisible({ timeout: 40_000 });

    // Everything the letter needs is asked for, because a case that does not exist yet can supply
    // nothing. One of those answers is what write-back saves.
    const decision = inputs.locator('[name="data[decision]"]');
    await expect(decision).toBeVisible({ timeout: 30_000 });
    await decision.fill('Het bezwaar is gegrond verklaard.');
    await page.waitForTimeout(2000);

    await page
      .getByRole('button', { name: /Verstuur brief|Genereer|Start|Opslaan/ })
      .last()
      .click({ force: true });

    // The case exists once the submission commits, and the letter's value is on it. Read from the
    // dossier the browser was taken to, which is the one this submission created.
    await expect(page).toHaveURL(/\/cases\/correspondentie\/document\/[0-9a-f-]{36}/, {
      timeout: 30_000,
    });
    await page.waitForTimeout(4000);
    const body = async () => (await page.locator('body').innerText()).replace(/\s+/g, ' ');
    await expect
      .poll(body, { timeout: 30_000, message: "the letter's value never reached the new case" })
      .toContain('Het bezwaar is gegrond verklaard.');
  });
});
