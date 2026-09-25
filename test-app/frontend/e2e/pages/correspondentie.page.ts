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
 * Open a dossier from the list, creating one first when the case has none.
 *
 * Valtimo polls in the background, so rows are never "stable" for long — hence the settle and the
 * forced click.
 */
export async function openDossier(page: Page): Promise<void> {
  await page.goto('/cases/correspondentie');
  await page.getByRole('navigation', { name: /Side navigation/i }).waitFor({ timeout: 20_000 });

  const rows = page.locator('table tbody tr');
  if ((await rows.count()) === 0) {
    await createDossier(page);
    await page.goto('/cases/correspondentie');
  }

  await rows.first().waitFor({ timeout: 20_000 });
  await page.waitForTimeout(3_000);
  await rows.first().click({ force: true });
  await expect(page.getByRole('button', { name: /^Start/ })).toBeVisible({ timeout: 20_000 });
}
