// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import { test, expect } from '@playwright/test';
import { ProcessLinkPage } from '../pages/process-link.page';

/**
 * The `check-job-status` action, chosen fresh on an unlinked activity.
 *
 * Unlike the other two actions, **no demo process links this one** — it is the polling alternative
 * to the catch-event correlation the demos use, so there is no stored configuration to open. This
 * file used to paper over that by navigating to `/plugins` and asserting `getByText(/Epistola/)`,
 * under a name claiming it checked the action's default values. Those defaults
 * (`epistolaRequestId` and friends) are not in the component at all — they are example names from
 * `docs/use-cases.md`.
 *
 * So this walks the path an author actually takes: pick an unlinked service task, choose the
 * Epistola plugin, choose this action, and check its form renders with the fields the backend
 * declares. That covers the seam the old test only claimed to: the action being **offered** at all
 * (a registration the frontend and backend have to agree on), and its configurator rendering
 * inside Valtimo's wizard.
 *
 * Nothing is saved — the wizard is abandoned, so the demo fixtures stay as deployed.
 */
test.describe('Check Job Status action configuration', () => {
  test('is offered on an unlinked activity, and renders its fields', async ({ page }) => {
    test.setTimeout(150_000);
    const processLinks = new ProcessLinkPage(page);

    // "Drie takken, één gekoppeld" exists for exactly this: st-gen-a is linked, st-plain-b and
    // st-plain-c are plain service tasks with no link.
    await processLinks.openProcess('Drie takken, één gekoppeld', 'st-plain-b');
    await processLinks.openActivity('st-plain-b');

    await processLinks.startNewEpistolaLink();

    // Every action the plugin registers is offered here, and that is a seam the frontend and
    // backend have to agree on: the action types come from the plugin definition the backend
    // serves, while their labels come from the frontend's own translations.
    for (const action of [
      /Controleer Taakstatus|Check Job Status/i,
      /Download Document/i,
      /Genereer Document|Generate Document/i,
      /Genereer Gekozen Brief|Generate Chosen Letter/i,
    ]) {
      await expect(processLinks.actionTile(action)).toBeVisible({ timeout: 15_000 });
    }

    await processLinks.chooseAction(/Controleer Taakstatus|Check Job Status/i);

    await expect(processLinks.region('epistola-check-status-form')).toBeVisible({
      timeout: 20_000,
    });

    // The four variables the action writes. Their names pair with the backend's @PluginProperty
    // keys, and a rename on one side leaves the field here unrendered.
    for (const testId of [
      'epistola-check-status-request-id-variable',
      'epistola-check-status-status-variable',
      'epistola-check-status-document-id-variable',
      'epistola-check-status-error-message-variable',
    ]) {
      await expect(processLinks.region(testId)).toBeVisible();
    }
  });
});
