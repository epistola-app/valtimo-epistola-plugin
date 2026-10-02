// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import { test, expect } from '@playwright/test';
import { ProcessLinkPage } from '../pages/process-link.page';

/**
 * The `download-document` action's configurator, opened on a link the demo actually declares.
 *
 * This file used to navigate to `/plugins` and assert `getByText(/Epistola/)` was visible, under a
 * name promising it checked the action's fields — and its comment named defaults
 * (`epistolaDocumentId`, `documentContent`) that exist in no component. It now opens the real link
 * and reads back what the fixture stored, which is the seam worth watching: the configurator's
 * field names have to match the backend's `@PluginProperty` keys, and a rename on one side shows up
 * here as an empty field rather than as a compile error anywhere.
 */
test.describe('Download Document action configuration', () => {
  test('opens the stored configuration of a linked download activity', async ({ page }) => {
    test.setTimeout(120_000);
    const processLinks = new ProcessLinkPage(page);

    // The objection demo downloads the acknowledgement it generated; see
    // config/case/objection/1.0.0/process-link/objection-handling.process-link.json.
    await processLinks.openProcess('Bezwaarprocedure', 'download-ack');
    await processLinks.openActivity('download-ack');

    await expect(processLinks.region('epistola-download-form')).toBeVisible({ timeout: 15_000 });

    // Read back exactly what the fixture declares. Asserting only that the fields exist would pass
    // against a configurator that renders empty, which is what a property-name mismatch looks like.
    await expect(processLinks.control('epistola-download-document-variable')).toHaveValue(
      'epistolaResult',
    );
    await expect(processLinks.control('epistola-download-resource-id-variable')).toHaveValue(
      'ackResourceId',
    );
    // Stored as TEMPORARY_RESOURCE; shown as a label, so matched loosely enough to survive
    // either language.
    await expect(processLinks.control('epistola-download-storage-target')).toHaveValue(
      /emporary|ijdelijk/,
    );
  });
});
