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
   * What the employee reads when a value breaks the template's own rule.
   *
   * The permit letter is the one with constrained fields, and it has both cases: `applicant.bsn`
   * carries a `pattern` *and* a description ("Burgerservicenummer (9 cijfers)"), while
   * `applicant.address.postalCode` carries a pattern and no description at all.
   *
   * Worth a browser rather than a unit test. The unit tests prove the transform writes the message
   * onto the component; only a rendered page proves Form.io *reads* it — and it reads a `pattern`
   * message by two different paths, so setting one and not the other would look correct and still
   * put a regular expression in front of a case worker.
   */
  test('explains a bad value in Dutch, without showing the expression', async ({ page }) => {
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

    const inputs = page.getByTestId('epistola-composer-inputs');
    await expect(inputs.locator('.page-link')).toHaveCount(3, { timeout: 30_000 });
    await inputs.locator('.page-link').filter({ hasText: 'Applicant' }).click();

    // A value that cannot satisfy `^\d{9}$`.
    const bsn = inputs.locator('[name="data[applicant.bsn]"]');
    await expect(bsn).toBeVisible({ timeout: 10_000 });
    await bsn.fill('not-a-bsn');
    await bsn.blur();

    const shown = async () => (await inputs.innerText()).replace(/\s+/g, ' ');

    // The contract's own description, which is the only text here written for a person.
    await expect
      .poll(shown, { timeout: 15_000, message: 'no message appeared for the invalid BSN' })
      .toContain('Burgerservicenummer (9 cijfers)');

    // And one valid value, which is worth more than any description of the rule. It comes from the
    // field's `examples` in the contract, so this also proves the keyword survives the round trip
    // through Epistola and back out as a generated form.
    await expect
      .poll(shown, { timeout: 15_000, message: 'the example from the contract was not offered' })
      .toContain('123456789');

    const afterBsn = await shown();
    // The regression this test exists for. The pattern a generated field carries is not even the
    // contract's own — the generator wraps it so Form.io's match behaves like JSON Schema's search
    // — so any of these on screen means a case worker is reading a regex this plugin assembled.
    expect(afterBsn).not.toContain('[\\s\\S]');
    expect(afterBsn).not.toContain('\\d{9}');
    expect(afterBsn).not.toContain('does not match the pattern');
    // Dutch, not Form.io's English default.
    expect(afterBsn).toContain('juiste vorm');

    // And the other path: a patterned field the contract says nothing readable about still gets a
    // sentence rather than an expression.
    const postalCode = inputs.locator('[name="data[applicant.address.postalCode]"]');
    // Offered before anything goes wrong, which is the better moment for it: an example in the
    // empty box prevents the error rather than explaining it.
    await expect(postalCode).toHaveAttribute('placeholder', '3511 LX');
    await postalCode.fill('nope');
    await postalCode.blur();

    // Polled on this field's own example, not on `juiste vorm`: the BSN message above already
    // contains that, so a looser wait returns instantly and reads the page before this field's
    // message has rendered.
    await expect
      .poll(shown, { timeout: 15_000, message: 'no message appeared for the invalid postal code' })
      .toContain('3511 LX');

    const afterPostalCode = await shown();
    expect(afterPostalCode).toContain('3511 LX');
    expect(afterPostalCode).not.toContain('[\\s\\S]');
    expect(afterPostalCode).not.toContain('[A-Z]{2}');
  });

  /**
   * What Epistola refuses, shown under the field it refused.
   *
   * The browser checks what the contract says about the fields it offered. It cannot check the
   * rest — most of a letter's data comes from the baseline mapping, which the browser neither
   * computed nor holds the contract for — so a letter can be refused over a field nobody was asked
   * about, and that answer exists only on the server.
   *
   * **The 422 is supplied here, and that is deliberate.** What this test covers is the half no unit
   * test can: that a JSON Pointer from the server ends up rendered against the right input, by
   * Form.io, in a wizard. Whether the pointers are read correctly out of a real problem body is
   * covered by `TemplateDataFindingsTest` and `EpistolaComposerResourceTest` against the shape the
   * contract specifies; whether Epistola sends it is covered by the Suite's own
   * `PreviewDocumentApiIT`. Supplying it also keeps this test honest about server version: an
   * Epistola older than the `template-data-invalid` work answers a refused preview with one
   * flattened sentence, and against such a server this path cannot be reached at all.
   */
  test('shows what Epistola refused under the field it refused', async ({ page }) => {
    test.setTimeout(180_000);

    // The documented answer for a letter refused over one field, named by pointer.
    let refusals = 0;
    await page.route('**/composer/preview', async (route) => {
      refusals += 1;
      await route.fulfill({
        status: 422,
        contentType: 'application/json',
        body: JSON.stringify({
          error: "Epistola could not render template 'bevestigingsbrief-vergunning'",
          fields: [
            {
              path: '/applicant/bsn',
              keyword: 'pattern',
              message: 'is geen geldig burgerservicenummer',
            },
          ],
        }),
      });
    });

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

    const inputs = page.getByTestId('epistola-composer-inputs');
    await expect(inputs.locator('.page-link')).toHaveCount(3, { timeout: 30_000 });

    // Fill every step so the composer stops waiting and asks for a render, with values the browser
    // is happy about — so the only complaint on screen can be the server's.
    for (const step of ['Property', 'Applicant', 'Activities']) {
      await inputs.locator('.page-link').filter({ hasText: step }).click();
      await page.waitForTimeout(500);
      for (const field of await inputs
        .locator('input[name^="data["]:visible, textarea[name^="data["]:visible')
        .all()) {
        const name = (await field.getAttribute('name')) ?? '';
        await field.fill(
          name.includes('bsn') ? '123456789' : name.includes('postalCode') ? '3511 LX' : 'proef',
        );
        await field.blur();
      }
    }

    await expect
      .poll(() => refusals, { timeout: 60_000, message: 'no preview was ever requested' })
      .toBeGreaterThan(0);

    // Named, one line per field, in the composer's own markup — see composer-findings.ts for why
    // not in Form.io's per-component error slots.
    const refused = page.getByTestId('epistola-composer-refused-fields');
    await expect(refused).toBeVisible({ timeout: 30_000 });

    const shown = (await refused.innerText()).replace(/\s+/g, ' ');
    // Epistola's own sentence about the rule that failed...
    expect(shown).toContain('is geen geldig burgerservicenummer');
    // ...against the field named the way the employee sees it named, not as a JSON Pointer.
    expect(shown).toContain('Bsn');
    expect(shown).not.toContain('/applicant/bsn');

    // And the flattened sentence is dropped: it is the same complaint, and showing both would say
    // there are two problems.
    await expect(page.getByTestId('epistola-composer-preview-error')).toBeHidden();
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

    // Not previewing yet: the letter cannot render until its required fields have values.
    await expect(page.getByTestId('epistola-composer-awaiting-input')).toBeVisible();
    await expect(page.getByTestId('epistola-composer-preview-error')).toBeHidden();

    // And it *does* preview once they do. Asserting only the waiting state was how a dead end hid
    // here: the grid's columns were demanded against the submission, where a row's fields can
    // never appear, so this letter waited however much was typed into it.
    // Filled from each field's own placeholder where it has one, which is the contract's example
    // for that field. Typing 'proef' everywhere is what this did before, and it only passed while
    // Epistola was refusing the letter for an unrelated reason: a BSN of 'proef' does not match
    // ^\\d{9}$, so a working server rejects the data and no letter ever renders. Using the example
    // is also the honest test of the examples feature — if an example is not valid for its own
    // field, this goes red.
    for (const step of ['Property', 'Applicant', 'Activities']) {
      await steps.filter({ hasText: step }).click();
      await page.waitForTimeout(500);
      for (const field of await inputs
        .locator('input[name^="data["]:visible, textarea[name^="data["]:visible')
        .all()) {
        const example = await field.getAttribute('placeholder');
        await field.fill(example?.trim() ? example : 'proef');
        await field.blur();
      }
    }

    await expect(page.getByTestId('epistola-composer-awaiting-input')).toBeHidden({
      timeout: 30_000,
    });

    // And the letter actually rendered. This used to accept Epistola's complaint as proof too,
    // which was the weaker claim it could make while the demo data could not satisfy the template:
    // every refusal counted as "the gate opened". Now that the fields are filled with values their
    // own schema accepts, nothing short of a rendered letter will do.
    await expect(page.getByTestId('epistola-composer-preview-pdf')).toBeVisible({
      timeout: 60_000,
    });
    await expect(page.getByTestId('epistola-composer-refused-fields')).toBeHidden();
    await expect(page.getByTestId('epistola-composer-preview-error')).toBeHidden();
    await expect(page.getByTestId('epistola-composer-error')).toBeHidden();
  });
});
