// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import { expect, test } from '@playwright/test';

/**
 * A refused letter is fixed by correcting the case, then generating again.
 *
 * The `permit` demo's start form leaves the BSN empty by default, so Epistola refuses the
 * confirmation letter. The process then offers "Corrigeer Dossiergegevens": Epistola's reason and
 * the case's own fields. This replaces the deprecated retry form, which corrected only the letter
 * (docs/user-task-fallback.md). What this walks is the part no unit test can show — that the reason
 * reaches the form, and that the correction lands on the case and the generate task maps it again.
 */
test.describe('Permit — correct the case after a refused letter', () => {
  test('shows why the letter was refused, and generates it once the case is corrected', async ({
    page,
  }) => {
    test.setTimeout(180_000);
    const visibleText = async () => (await page.locator('body').innerText()).replace(/\s+/g, ' ');

    await page.goto('/cases/permit');
    await page.getByRole('navigation', { name: /Side navigation/i }).waitFor({ timeout: 20_000 });
    await page.getByRole('button', { name: 'Creëer Nieuw Dossier' }).first().click();

    // The start form's defaults are valid apart from the BSN, which it leaves empty on purpose.
    await page.locator('input[name="data[applicant.firstName]"]').waitFor({ timeout: 20_000 });
    for (const field of await page
      .locator('.formio-component-activities')
      .locator('input:visible, textarea:visible')
      .all()) {
      if (!(await field.inputValue())) {
        await field.fill((await field.getAttribute('type')) === 'number' ? '5000' : 'Dakkapel');
      }
    }
    await page.getByRole('button', { name: 'Indienen' }).click();

    // Epistola refuses the letter, and the process routes to the correction task.
    await expect(page).toHaveURL(/\/cases\/permit\/document\/[0-9a-f-]{36}/, { timeout: 30_000 });
    await expect
      .poll(
        async () => {
          await page.reload();
          await page.waitForTimeout(2_000);
          return visibleText();
        },
        { timeout: 90_000, intervals: [3_000], message: 'the correction task never appeared' },
      )
      .toContain('Corrigeer Dossiergegevens');
    await page.getByText('Corrigeer Dossiergegevens').first().click();

    // Epistola's own reason, naming the field, so the employee knows what to fix. A disabled text
    // area renders as text rather than a textarea, which is also what makes it read-only.
    const reason = page.locator('.formio-component-pv\\:generationError');
    await expect(reason).toBeVisible({ timeout: 20_000 });
    await expect(reason).toContainText('/applicant/bsn');

    // The case's own values are there to correct, not a copy of the letter's.
    const bsn = page.locator('input[name="data[applicant.bsn]"]');
    await expect(page.locator('input[name="data[applicant.firstName]"]')).toHaveValue('Jan');
    await bsn.fill('123456782');
    await page.getByRole('button', { name: 'Opnieuw genereren' }).click();

    // Generated from the corrected case: the process reaches the review task.
    await expect
      .poll(
        async () => {
          await page.reload();
          await page.waitForTimeout(2_000);
          return visibleText();
        },
        { timeout: 90_000, intervals: [3_000], message: 'the letter was never generated' },
      )
      .toContain('Controleer Bevestigingsbrief');
  });
});
