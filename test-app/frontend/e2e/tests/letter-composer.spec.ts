// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import { test, expect, type Page } from '@playwright/test';

/**
 * The letter composer on the Correspondentie case.
 *
 * Walks what a case worker actually does: start a dossier, open "Kies een brief", pick a letter,
 * and see the component work out what the case could not supply. Two letters, deliberately:
 *
 * - "Ontvangstbevestiging bezwaarschrift" — the baseline mapping fills its whole contract, so the
 *   employee is asked for nothing;
 * - "Besluit op bezwaar" — the case does not know the ruling, so exactly those three fields appear.
 *
 * Nothing in the form says which fields those are. They follow from evaluating the mapping against
 * this case, which is the property worth watching in a browser: the backend test asserts the same
 * thing, but only a rendered page proves the component asks for them, keeps the preview beside the
 * inputs, and stores a value the process can generate from.
 *
 * Needs a reachable Epistola, since the preview renders a real PDF.
 */

const DECISION_FIELDS = ['decisionType', 'decision', 'motivation'];

async function startDossier(page: Page) {
  await page.goto('/cases/correspondentie');
  await page.getByRole('navigation', { name: /Side navigation/i }).waitFor({ timeout: 20_000 });
  // An empty case list shows the button twice (header and empty state).
  await page.getByRole('button', { name: 'Creëer Nieuw Dossier' }).first().click();

  // The case has exactly one startable process, so this opens its start form directly. That is
  // deliberate: with two, Valtimo shows a picker whose tiles reload the app instead of opening the
  // form, which would leave the demo unstartable.
  //
  // The start form ships with valid defaults for the whole schema, so nothing has to be typed —
  // what matters here is the letter composer on the task that follows.
  await page.locator('input[name="data[objector.firstName]"]').waitFor({ timeout: 20_000 });
  await page.getByRole('button', { name: 'Start correspondentie' }).click();
}

test.describe('Letter composer — pick a letter, fill in what the case cannot supply', () => {
  test('asks for the decision fields only, and previews the chosen letter', async ({ page }) => {
    test.setTimeout(180_000);

    await startDossier(page);
    await page
      .getByRole('button', { name: /Start|Aanmaken|Opslaan/ })
      .first()
      .click();

    const chooseTask = page.getByText('Kies een brief').first();
    await expect(chooseTask).toBeVisible({ timeout: 20_000 });
    await chooseTask.click();

    const composer = page.getByTestId('epistola-composer');
    await expect(composer).toBeVisible({ timeout: 20_000 });
    const select = page.getByTestId('epistola-composer-select');

    // The acknowledgement needs nothing: its whole contract comes from the case.
    await select.selectOption('ontvangstbevestiging-bezwaar');
    await expect(page.getByTestId('epistola-composer-nothing-to-ask')).toBeVisible({
      timeout: 30_000,
    });

    // The decision does: the case does not know the ruling.
    await select.selectOption('besluit-bezwaar');
    const inputs = page.getByTestId('epistola-composer-inputs');
    await expect(inputs.locator('input, textarea')).toHaveCount(DECISION_FIELDS.length, {
      timeout: 30_000,
    });
    for (const field of DECISION_FIELDS) {
      await expect(inputs.locator(`[name="data[${field}]"]`)).toBeVisible();
    }

    // No preview yet: this letter's required fields are empty, and rendering it would return
    // Epistola's validation error for the very fields the employee was just asked to fill.
    await expect(page.getByTestId('epistola-composer-awaiting-input')).toBeVisible();
    await expect(page.getByTestId('epistola-composer-preview-error')).toBeHidden();

    // Filling them in makes the letter renderable, and the preview appears (debounced).
    await inputs.locator('[name="data[decisionType]"]').fill('gegrond');
    await inputs.locator('[name="data[decision]"]').fill('Het bezwaar is gegrond verklaard.');
    await inputs.locator('[name="data[motivation]"]').fill('Voldoet aan de welstandscriteria.');
    await expect(page.getByTestId('epistola-composer-preview-pdf')).toBeVisible({
      timeout: 60_000,
    });
    await expect(page.getByTestId('epistola-composer-preview-error')).toBeHidden();

    // Submitting completes the task, which is what hands the chosen letter to the generate task.
    await page.getByRole('button', { name: 'Genereer brief' }).click();
    const visibleText = async () => (await page.locator('body').innerText()).replace(/\s+/g, ' ');
    await expect
      .poll(visibleText, { timeout: 30_000, message: 'the choose-letter task never completed' })
      .not.toContain('Kies een brief Open');
  });
});
