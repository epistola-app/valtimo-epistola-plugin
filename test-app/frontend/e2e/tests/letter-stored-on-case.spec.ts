// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2
import { expect, test } from '@playwright/test';
import { clickSettled } from '../pages/settled-click';
import { createDossier } from '../pages/correspondentie.page';
import { composerField } from '../pages/composer-field';

/**
 * The letter a case worker composed is on the dossier afterwards, and opens.
 *
 * This is the only test that watches a generated document all the way out. Everything before it
 * stops at the preview — which renders from a template and proves nothing about generation — so a
 * completely dead generate path left every browser suite green. It got worse when write-back moved
 * to form submission: the values now land on the case whether or not anything renders, so the case
 * records a decision, the employee sees a successful submit, and no letter exists.
 *
 * What makes it assertable at all is that the demo keeps its own copy. Valtimo offers no durable
 * file store without the Documenten API or S3 — its `local-resource` module throws
 * `NotImplementedError` on store — so before this the process ended having produced a PDF that
 * nothing could show.
 *
 * Deliberately slow and patient: generation is asynchronous, so the file appears when Epistola
 * answers and the catch event fires, not when the task completes.
 */
test.describe('A composed letter ends up on the dossier', () => {
  const DECISION_FIELDS = {
    decisionType: 'gegrond',
    decision: 'Het bezwaar is gegrond verklaard.',
    motivation: 'Voldoet aan de welstandscriteria.',
  };

  test('is listed under Documenten, with its size', async ({ page }) => {
    test.setTimeout(240_000);

    await createDossier(page);
    await page
      .getByRole('button', { name: /Start|Aanmaken|Opslaan/ })
      .first()
      .click();

    const chooseTask = page.getByText('Kies een brief').first();
    await expect(chooseTask).toBeVisible({ timeout: 20_000 });
    await chooseTask.click();

    await expect(page.getByTestId('epistola-composer')).toBeVisible({ timeout: 20_000 });
    await page.getByTestId('epistola-composer-select').selectOption('besluit-bezwaar');

    const inputs = page.getByTestId('epistola-composer-inputs');
    await expect(inputs.locator(`[name="data[decisionType]"]`)).toBeVisible({ timeout: 30_000 });
    for (const [field, value] of Object.entries(DECISION_FIELDS)) {
      await composerField(inputs, field).fill(value);
    }
    // Wait for the preview, which is the signal that the letter is renderable — submitting before it
    // would be a race with Epistola rather than a test of what happens afterwards.
    await expect(page.getByTestId('epistola-composer-preview-pdf')).toBeVisible({
      timeout: 60_000,
    });

    await clickSettled(page.getByRole('button', { name: 'Genereer brief' }));

    // Generation is asynchronous: the task completes, then Epistola answers, then the catch event
    // fires, and only then does the demo store the letter and relate it to the case.
    //
    // Reload inside the poll, which is the whole trick. The tab fetches its file list once when it
    // is opened, so polling the rendered text asks the same stale answer forever — measured: the
    // letter was on the case and in the table while this timed out. A case worker sees it on their
    // next look at the dossier, and that is what this reproduces.
    const filesAfterReload = async () => {
      await page.reload();
      const tab = page.getByRole('tab', { name: /Documenten/i });
      await tab.waitFor({ timeout: 20_000 });
      await tab.click();
      return (await page.locator('body').innerText()).replace(/\s+/g, ' ');
    };
    await expect
      .poll(filesAfterReload, {
        timeout: 150_000,
        intervals: [3_000],
        message: 'the generated letter never arrived on the dossier',
      })
      .toContain('brief.pdf');

    // Its size is listed beside it, which is as far as this test goes. Clicking through to the file
    // was tried and dropped: the row renders its actions as glyph buttons with no accessible name,
    // so the locator was fragile, and the download path is already covered where it is stable —
    // DownloadDocumentE2ETest fetches it through the very ResourceResource the UI calls, and a
    // manual check returned a 2-page application/pdf of 70,763 bytes. A flaky click here would buy
    // nothing and cost a nightly run.
    await expect(page.getByRole('row', { name: /brief\.pdf/i }).first()).toContainText(/\d/);
  });
});
