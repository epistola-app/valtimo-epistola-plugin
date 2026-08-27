// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import { test, expect, type Page } from '@playwright/test';

/**
 * Form Flow task transition, with the Epistola document preview on the confirmation step.
 *
 * Walks the "Form Flow voorbeeld" case: start a dossier, open "Genereer brief",
 * go through the two form flow steps, and assert that the regular "Vervolgtaak"
 * becomes visible **without reloading the page**.
 *
 * The preview sits on the second step, so it is on screen — and may still be
 * loading — when "Doorgaan" is clicked. That is the arrangement worth watching:
 * it is the only one where a preview request can be in flight as the task
 * completes.
 *
 * The test deliberately never calls page.reload() after "Doorgaan" — a passing
 * run means the transition is visible on the live page. It needs a reachable
 * Epistola, since the confirmation step renders a real preview.
 */

const CASE_NAME = 'Form Flow voorbeeld';

async function openCaseList(page: Page) {
  // Navigate straight to the dossier list. The side navigation nests the case
  // under a collapsible "Dossiers" group, which is incidental to what we test.
  // The app polls in the background, so 'networkidle' never settles; wait for
  // concrete elements instead.
  await page.goto('/cases/form-flow-demo');
  await page.getByRole('navigation', { name: /Side navigation/i }).waitFor({ timeout: 20_000 });
}

test.describe('Form Flow voorbeeld — transition with a preview on the confirmation step', () => {
  test('Vervolgtaak appears after Doorgaan without a page refresh', async ({ page }) => {
    test.setTimeout(120_000);
    // An Angular dev build logs plenty of unrelated noise, so rather than
    // demanding a silent console we watch for the failure class this baseline
    // exists to detect: the form flow's onComplete expression blowing up, which
    // is what a rejected document write surfaces as.
    const flowErrors: string[] = [];
    page.on('console', (msg) => {
      const text = msg.text();
      if (
        msg.type() === 'error' &&
        /completeTask|Error while executing expression|Internal Server Error/i.test(text)
      ) {
        flowErrors.push(text);
      }
    });

    // 404s Valtimo returns by design for optional case furniture. They are not
    // specific to this case: `objection` and `permit` also declare a summary tab
    // without shipping a summary form, and the intermediate-submission lookup
    // 404s until something has actually been saved.
    const BENIGN_404 = [
      /\/header-widget/,
      /\/form-definition\/.*\.summary/,
      /\/form\/intermediate\/submission/,
    ];
    const failedRequests: string[] = [];
    page.on('response', (res) => {
      const url = res.url();
      if (res.status() >= 400 && url.includes('/api/') && !BENIGN_404.some((r) => r.test(url))) {
        failedRequests.push(`${res.status()} ${res.request().method()} ${url}`);
      }
    });

    await openCaseList(page);

    // Start a new dossier through the start form.
    await page.getByRole('button', { name: 'Creëer Nieuw Dossier' }).click();
    const titleField = page.locator('input[name="data[title]"]');
    await titleField.waitFor({ timeout: 15_000 });
    await titleField.fill('Playwright Form Flow-test');
    await page.getByRole('button', { name: 'Start Form Flow' }).click();

    // The dossier opens; the "Genereer brief" task must be listed.
    const generateTask = page.getByText('Genereer brief').first();
    await expect(generateTask).toBeVisible({ timeout: 20_000 });
    await generateTask.click();

    // Form flow step 1 — enter the subject and continue to the confirmation.
    const subjectField = page.locator('input[name="data[subject]"]');
    await subjectField.waitFor({ timeout: 20_000 });
    await subjectField.fill('Bevestiging lokale Form Flow-test');
    await page.getByRole('button', { name: 'Naar bevestiging' }).click();

    // Form flow step 2 — this completes the BPMN task.
    const continueButton = page.getByRole('button', { name: 'Doorgaan' });
    await expect(continueButton).toBeVisible({ timeout: 20_000 });
    await continueButton.click();

    // The assertions that matter, against the *live* page: no reload, no
    // navigation, only what the app does on its own.
    //
    // Assert on the page's visible text rather than a `getByText(...)` locator:
    // 'Vervolgtaak' also exists in hidden markup, so a locator-based check goes
    // green even when the task never completed and the form flow modal is still
    // open. Visible text distinguishes the two states.
    const visibleText = async () => (await page.locator('body').innerText()).replace(/\s+/g, ' ');

    // The form flow modal closes only once the BPMN task actually completed.
    await expect(page.getByRole('button', { name: 'Doorgaan' })).toBeHidden({ timeout: 20_000 });

    // 'Vervolgtaak Open' is the open-task row in the "Mijn taken" list. While the
    // task flow is stuck that row reads 'Genereer brief Open' instead, so this
    // substring is what actually separates a completed transition from a failed
    // one. Plain 'Vervolgtaak' would not: it also occurs in hidden markup.
    await expect
      .poll(visibleText, { timeout: 20_000, message: 'follow-up task never became the open task' })
      .toContain('Vervolgtaak Open');

    // Valtimo confirms the completion with a toast, so 'Genereer brief' stays on
    // screen afterwards — its presence says nothing, but the toast does.
    await expect
      .poll(visibleText, {
        timeout: 20_000,
        message: 'no completion notification for the finished task',
      })
      .toContain('Genereer brief is succesvol afgerond');

    expect(
      failedRequests,
      `failing API calls during the flow:\n${failedRequests.join('\n')}`,
    ).toEqual([]);
    expect(flowErrors, `form flow errors during the flow:\n${flowErrors.join('\n')}`).toEqual([]);
  });
});
