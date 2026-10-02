// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import { expect, type Page } from '@playwright/test';

/**
 * Getting to a Correspondentie dossier, shared by the letter-composer suites.
 *
 * Both suites need one, and neither may assume the other ran: a suite that opens "the first
 * dossier" passes only on a database that already has one, which is exactly how it fails after a
 * reset.
 */

/**
 * Create a dossier through the composer process's start form, and land on its detail page.
 *
 * The case has one process that creates dossiers, so this opens its start form directly. With two,
 * Valtimo shows a process picker whose tiles reload the app instead of opening the form — which is
 * why each demo flow has exactly one such process.
 */
export async function createDossier(page: Page): Promise<void> {
  await page.goto('/cases/correspondentie');
  await page.getByRole('navigation', { name: /Side navigation/i }).waitFor({ timeout: 20_000 });
  // An empty case list shows the button twice (header and empty state).
  await page.getByRole('button', { name: 'Creëer Nieuw Dossier' }).first().click();

  // The start form ships with valid defaults for the whole schema, so nothing has to be typed.
  await page.locator('input[name="data[objector.firstName]"]').waitFor({ timeout: 20_000 });
  await page.getByRole('button', { name: 'Start correspondentie' }).click();
}

/**
 * Start one of the dossier's own processes from its Start menu.
 *
 * The menu fills in after the dossier loads, and clicking Start toggles it — so wait for the item
 * to be *visible* rather than merely present, or a retry closes the menu again and the click lands
 * on a hidden element.
 */
export async function startFromMenu(page: Page, name: string): Promise<void> {
  const item = page.getByText(name, { exact: true });
  const startButton = page.getByRole('button', { name: /^Start/ }).first();

  await expect
    .poll(
      async () => {
        if (await item.isVisible().catch(() => false)) {
          return true;
        }
        await startButton.click({ force: true });
        await page.waitForTimeout(1_500);
        return item.isVisible().catch(() => false);
      },
      { timeout: 40_000, message: `"${name}" never appeared in the Start menu` },
    )
    .toBe(true);

  await item.click({ force: true });
}
