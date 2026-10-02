// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import { test, expect } from '@playwright/test';
import { ProcessLinkPage } from '../pages/process-link.page';

/**
 * The `generate-document` action's configurator, opened on the link the demo declares.
 *
 * This file used to mock four Epistola endpoints, navigate to `/plugins`, assert the URL, and then
 * check its own fixtures back through `page.request` — which bypasses `page.route` entirely, so it
 * was asserting that the real backend served a template called "Invoice Template". It rendered no
 * part of the configurator it was named after. Nothing is mocked now: the point of a browser test
 * here is that the real component, fed the real stored link, shows what was stored.
 *
 * The configurator's own logic is covered far more thoroughly by
 * `generate-document-configuration.component.spec.ts` (Jest). What only a browser can show is that
 * it renders at all inside Valtimo's process-link wizard, and that the values survive the round
 * trip through the backend's `@PluginProperty` names.
 */
test.describe('Generate Document action configuration', () => {
  test('opens the stored configuration of a linked generate activity', async ({ page }) => {
    test.setTimeout(120_000);
    const processLinks = new ProcessLinkPage(page);

    // config/case/example/1.0.0/process-link/single-document.process-link.json
    await processLinks.openProcess('Generate Single Document', 'generate-document');
    await processLinks.openActivity('generate-document');

    await expect(processLinks.region('epistola-generate-form')).toBeVisible({ timeout: 20_000 });

    // Read back what the fixture stored, rather than merely that the fields exist — a configurator
    // that renders empty is exactly what a property-name mismatch looks like.
    //
    // Catalog and template show their **names**, not the ids the link stores
    // (`municipality-demo` / `example-template`). That is the stronger assertion: the names only
    // appear if the configurator resolved those ids against a reachable Epistola.
    await expect(processLinks.control('epistola-generate-catalog-id')).toHaveValue(
      /Municipality Demo/,
    );
    await expect(processLinks.control('epistola-generate-template-id')).toHaveValue(
      /Example Template/,
    );
    await expect(processLinks.control('epistola-generate-result-process-variable')).toHaveValue(
      'epistolaResult',
    );

    // Filename is an expression, edited in a contenteditable rather than an input, so it is read as
    // text. The fixture stores it quoted (`"example-document.pdf"`).
    await expect(processLinks.region('epistola-generate-filename-expression-input')).toContainText(
      'example-document.pdf',
    );
  });

  test('offers all three variant-selection modes, and the mapping the template needs', async ({
    page,
  }) => {
    test.setTimeout(120_000);
    const processLinks = new ProcessLinkPage(page);

    await processLinks.openProcess('Generate Single Document', 'generate-document');
    await processLinks.openActivity('generate-document');
    await expect(processLinks.region('epistola-generate-form')).toBeVisible({ timeout: 20_000 });

    // The three modes are the action's documented contract: default, explicit variantId, and
    // attribute-based. The stored link names none, so it sits on the default.
    await expect(processLinks.region('epistola-generate-variant-mode-toggle')).toBeVisible();
    await expect(processLinks.region('epistola-generate-variant-mode-explicit')).toBeVisible();
    await expect(processLinks.region('epistola-generate-variant-mode-attributes')).toBeVisible();

    // The mapping builder is driven by the template's contract, fetched from Epistola — so a row
    // per contract field is also proof the backend reached the server and returned this template.
    // example-template declares exactly one field, firstName.
    await expect(processLinks.region('epistola-mapping-builder')).toBeVisible();
    await expect(processLinks.region('epistola-mapping-row-firstName')).toBeVisible();
    // Also an expression editor; its text carries zero-width spaces around the expression, so this
    // matches the expression itself rather than the whole string.
    await expect(processLinks.region('epistola-mapping-field-input-firstName-input')).toContainText(
      /\$doc\.firstName/,
    );
  });
});
