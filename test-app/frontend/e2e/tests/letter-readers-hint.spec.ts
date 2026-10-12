/*
 * Copyright 2025 Epistola.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: EUPL-1.2
 */

import { expect, test } from '@playwright/test';

/**
 * The composer configurator says which generate tasks read the letter.
 *
 * Worth a browser test rather than only unit tests: the key it reports on comes from Form.io's edit
 * dialog (`root.data.key`), and the case type comes from the page's own URL. Both are assumptions
 * about a host this plugin does not own, and a unit test that sets the input directly would pass
 * with either of them broken.
 */
test.describe('Letter composer — what reads this letter', () => {
  const KIES_BRIEF = '49fb641d-eaae-4872-8835-d89b571189a4';

  test('names the generate task on the composer being edited', async ({ page }) => {
    test.setTimeout(120_000);

    await page.goto(`/case-management/case/correspondentie/version/1.0.0/forms/${KIES_BRIEF}`);

    // The builder mounts the saved form; the composer is one of its components.
    const composer = page.locator('[ref="component"]', { hasText: 'Brief' }).first();
    await composer.waitFor({ timeout: 30_000 });

    // Form.io shows its per-component actions on hover; the pencil opens the settings dialog.
    await composer.hover();
    await page.locator('[ref="editComponent"]').first().click();

    const hint = page.getByTestId('epistola-letter-set-readers');
    await expect(hint).toBeVisible({ timeout: 30_000 });
    // kies-brief stores its letter on pv:epistolaLetter, which generate-chosen-letter reads.
    await expect(hint).toContainText('generate-chosen-letter');
  });

  test('says nothing at all when the lookup fails', async ({ page }) => {
    test.setTimeout(120_000);

    // Two jobs. It pins the fail-soft rule — a hint beside a field must not accuse a working
    // configuration of having no reader just because a request failed — and it proves the test
    // above is not passing for some other reason: break only this call and the line disappears.
    await page.route('**/letter-readers*', (route) => route.fulfill({ status: 500, body: '{}' }));

    await page.goto(`/case-management/case/correspondentie/version/1.0.0/forms/${KIES_BRIEF}`);

    const composer = page.locator('[ref="component"]', { hasText: 'Brief' }).first();
    await composer.waitFor({ timeout: 30_000 });
    await composer.hover();
    await page.locator('[ref="editComponent"]').first().click();

    // The settings dialog opens either way — only the hint is missing.
    await expect(page.getByTestId('epistola-letter-set')).toBeVisible({ timeout: 30_000 });
    await expect(page.getByTestId('epistola-letter-set-readers')).toHaveCount(0);
  });
});
