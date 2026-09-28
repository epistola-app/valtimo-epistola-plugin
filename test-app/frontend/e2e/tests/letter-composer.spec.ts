// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import { test, expect } from '@playwright/test';
import { createDossier } from '../pages/correspondentie.page';

/**
 * The letter composer on the Correspondentie case.
 *
 * Walks what a case worker actually does: start a dossier, open "Kies een brief", pick a letter,
 * and see the component work out what the case could not supply. Two letters, deliberately:
 *
 * - "Ontvangstbevestiging bezwaarschrift" — the baseline mapping fills its whole contract, so the
 *   employee is asked for nothing;
 * - "Besluit op bezwaar" — the case does not know the ruling, so exactly those three fields appear;
 * - "Bevestiging omgevingsvergunning" — a permit letter a bezwaar case knows nothing about, so all
 *   twelve of its required fields are asked for and the form is stepped through instead.
 *
 * Nothing in the form says which fields those are. They follow from evaluating the mapping against
 * this case, which is the property worth watching in a browser: the backend test asserts the same
 * thing, but only a rendered page proves the component asks for them, keeps the preview beside the
 * inputs, and stores a value the process can generate from.
 *
 * Needs a reachable Epistola, since the preview renders a real PDF.
 */

const DECISION_FIELDS = ['decisionType', 'decision', 'motivation'];

test.describe('Letter composer — pick a letter, fill in what the case cannot supply', () => {
  test('asks for the decision fields only, and previews the chosen letter', async ({ page }) => {
    test.setTimeout(180_000);

    await createDossier(page);
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

  /**
   * A letter with a lot to fill in is stepped through rather than stacked in one column.
   *
   * The permit confirmation is deliberately a letter this case has no data for: the baseline
   * mapping fills none of its twelve required fields, so the composer crosses the threshold and
   * sections the form. Worth a browser: the step titles come from the template's own contract, and
   * Formio renders a wizard's navigation itself — which is where a Cancel and a Submit Form button
   * appeared inside a Valtimo form that already has one.
   */
  test('steps through a letter the case cannot fill at all', async ({ page }) => {
    test.setTimeout(180_000);

    await createDossier(page);
    await page
      .getByRole('button', { name: /Start|Aanmaken|Opslaan/ })
      .first()
      .click();

    const chooseTask = page.getByText('Kies een brief').first();
    await expect(chooseTask).toBeVisible({ timeout: 20_000 });
    await chooseTask.click();

    await expect(page.getByTestId('epistola-composer')).toBeVisible({ timeout: 20_000 });
    await page.getByTestId('epistola-composer-select').selectOption('bevestigingsbrief-vergunning');

    // Three steps, two of them named by the contract's own groups.
    const inputs = page.getByTestId('epistola-composer-inputs');
    const steps = inputs.locator('.page-link');
    await expect(steps).toHaveCount(3, { timeout: 30_000 });
    // Two named by the contract's own groups, and one named after the single field it holds
    // rather than by number.
    await expect(steps.filter({ hasText: 'Applicant' })).toHaveCount(1);
    await expect(steps.filter({ hasText: 'Property' })).toHaveCount(1);
    await expect(steps.filter({ hasText: 'Activities' })).toHaveCount(1);

    // Only the navigation a step can use — never Formio's own Cancel or Submit, which would
    // submit the nested form rather than the task. Matched on what the employee reads: Formio
    // gives these buttons a verbose aria-label ("Next button. Click to go to the next tab").
    const button = (label: RegExp) => inputs.locator('button').filter({ hasText: label });
    await expect(button(/^Next$/)).toBeVisible();
    await expect(button(/^(Cancel|Submit Form)$/)).toHaveCount(0);
    await expect(button(/^Previous$/)).toHaveCount(0);

    // Steps are reachable directly, not only by paging through the ones between.
    await steps.filter({ hasText: 'Applicant' }).click();
    await expect(inputs.locator('[name="data[applicant.bsn]"]')).toBeVisible({ timeout: 10_000 });
    await expect(button(/^Previous$/)).toBeVisible();

    // Still not previewing: the letter cannot render until its required fields have values.
    await expect(page.getByTestId('epistola-composer-awaiting-input')).toBeVisible();
    await expect(page.getByTestId('epistola-composer-preview-error')).toBeHidden();
  });
});
