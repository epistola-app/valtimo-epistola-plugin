// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import { test, expect } from '@playwright/test';
import { createDossier, startFromMenu } from '../pages/correspondentie.page';

/**
 * The composer on a step of a Valtimo **Form Flow**, rather than on a plain task form.
 *
 * Worth its own browser test because the flow changes how the configuration is found, and nothing
 * about that is visible in the component. A form link hands the backend its form directly; a
 * form-flow link hands it a flow, whose steps store their forms by *name*, unique only within a
 * case definition. That lookup had no browser coverage at all, and its failure mode is a composer
 * that renders and then offers no letters — which looks like a configuration mistake rather than a
 * bug.
 *
 * The flow also puts the composer somewhere the earlier suites never did: a step that is not the
 * last one. Completing step 1 has to carry the chosen letter forward to the step that completes the
 * task, or the generate task gets nothing.
 *
 * What this does not assert is the generated document arriving back on the case — the catch event
 * behind it is covered by `DownloadDocumentE2ETest`, which can watch the process rather than the
 * page. Needs a reachable Epistola, since the preview renders a real PDF.
 */

const DECISION_FIELDS = ['decisionType', 'decision', 'motivation'];

/** What the step's own component offers — an empty select is how a failed lookup presents. */
const OFFERED_LETTERS = [
  'ontvangstbevestiging-bezwaar',
  'besluit-bezwaar',
  'bevestigingsbrief-vergunning',
];

test.describe('Letter composer — on a form-flow step', () => {
  test('offers its letters inside a flow, and carries the chosen one to the completing step', async ({
    page,
  }) => {
    test.setTimeout(180_000);

    // A fresh dossier, not whichever one is first: the last assertion is that this task is gone,
    // and a dossier carrying leftover tasks of the same name from an earlier run can never satisfy
    // it.
    await createDossier(page);
    await page
      .getByRole('button', { name: /Start|Aanmaken|Opslaan/ })
      .first()
      .click();

    await startFromMenu(page, 'Brief kiezen in een form flow');

    // The process has a start form of its own, before the task that carries the composer.
    await page.getByRole('button', { name: 'Beginnen' }).click({ force: true });

    // Step 1 of the flow. Valtimo polls the task list, so a click can land on a row that is being
    // re-rendered and open nothing at all — retry until the flow's modal is actually up.
    const chooseTask = page.getByText('Brief kiezen (form flow)').first();
    await expect(chooseTask).toBeVisible({ timeout: 30_000 });
    const composer = page.getByTestId('epistola-composer');

    await expect
      .poll(
        async () => {
          if (await composer.isVisible().catch(() => false)) {
            return true;
          }
          await chooseTask.click({ force: true });
          await page.waitForTimeout(2_000);
          return composer.isVisible().catch(() => false);
        },
        { timeout: 60_000, message: 'the form flow never opened its first step' },
      )
      .toBe(true);

    // A composer that rendered but found no configuration would show an empty select, so the
    // options are what proves the form-flow lookup resolved.
    const select = page.getByTestId('epistola-composer-select');
    for (const letter of OFFERED_LETTERS) {
      await expect(select.locator(`option[value="${letter}"]`)).toHaveCount(1, { timeout: 30_000 });
    }

    await select.selectOption('besluit-bezwaar');
    const inputs = page.getByTestId('epistola-composer-inputs');
    for (const field of DECISION_FIELDS) {
      await expect(inputs.locator(`[name="data[${field}]"]`)).toBeVisible({ timeout: 30_000 });
    }

    await inputs.locator('[name="data[decisionType]"]').fill('gegrond');
    await inputs.locator('[name="data[decision]"]').fill('Het bezwaar is gegrond verklaard.');
    await inputs.locator('[name="data[motivation]"]').fill('Gekozen in een form flow.');
    await expect(page.getByTestId('epistola-composer-preview-pdf')).toBeVisible({
      timeout: 60_000,
    });

    // Step 1 → step 2. The composer is behind us now, and the letter has to have come along.
    await page.getByRole('button', { name: 'Doorgaan' }).click({ force: true });
    const confirm = page.getByRole('button', { name: 'Verstuur brief' });
    await expect(confirm).toBeVisible({ timeout: 30_000 });
    await expect(composer).toBeHidden();

    await confirm.click({ force: true });

    // Completing the last step completes the task, which is what hands the letter to the generate
    // task. Asserted on the page's visible text: Valtimo renders task names into hidden markup
    // too, so a visibility check on the name can pass while the task never ran.
    const visibleText = async () => (await page.locator('body').innerText()).replace(/\s+/g, ' ');
    await expect
      .poll(visibleText, { timeout: 30_000, message: 'the form flow never completed its task' })
      .not.toContain('Brief kiezen (form flow) Open');
  });
});
