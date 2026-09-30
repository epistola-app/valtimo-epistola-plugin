// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import { test, expect } from '@playwright/test';
import { PluginManagementPage } from '../pages/plugin-management.page';

/**
 * The plugin's own configuration form, as an administrator meets it.
 *
 * Every field here comes from a backend `@PluginProperty`, and the pairing is by name — a rename on
 * one side and not the other produces a field that silently never arrives. That is what these
 * check: that the form Valtimo builds from the plugin definition still has the properties the
 * plugin declares, with `apiKey` masked and `tenantId`'s slug pattern enforced.
 *
 * Selectors live in {@link PluginManagementPage}, which documents why `getByLabel` cannot be used
 * on this screen.
 */
test.describe('Epistola Plugin Configuration', () => {
  let pluginPage: PluginManagementPage;

  test.beforeEach(async ({ page }) => {
    pluginPage = new PluginManagementPage(page);
    await pluginPage.navigate();
  });

  test('should show Epistola in the plugin list', async () => {
    await pluginPage.openAddPluginModal();

    // Scoped to the tile: the name also appears in a heading and a description span, so an
    // unscoped text match resolves to three elements and fails strict mode.
    await expect(pluginPage.tile('Epistola Document Suite')).toBeVisible();
  });

  test('should render all plugin configuration fields', async () => {
    await pluginPage.openAddPluginModal();
    await pluginPage.selectEpistolaPlugin();

    for (const label of [
      /Configuration name|Configuratienaam/i,
      /Base URL/i,
      /API Key/i,
      /Tenant ID/i,
      /Default Environment|Standaard Omgeving/i,
    ]) {
      await expect(pluginPage.field(label)).toBeVisible();
    }
  });

  test('should mask API Key as password field', async () => {
    await pluginPage.openAddPluginModal();
    await pluginPage.selectEpistolaPlugin();

    await expect(pluginPage.field(/API Key/i)).toHaveAttribute('type', 'password');
  });

  test('should require mandatory fields before saving', async () => {
    await pluginPage.openAddPluginModal();
    await pluginPage.selectEpistolaPlugin();

    await expect(pluginPage.saveButton).toBeDisabled();
  });

  test('should validate tenantId slug format', async () => {
    await pluginPage.openAddPluginModal();
    await pluginPage.selectEpistolaPlugin();

    await pluginPage.field(/Configuration name|Configuratienaam/i).fill('Test Config');
    await pluginPage.field(/Base URL/i).fill('https://api.epistola.app');
    await pluginPage.field(/API Key/i).fill('test-api-key');

    // Asserting "save is disabled" on its own proves nothing here — it is disabled while the form
    // is incomplete too, so this test would pass even if the tenant field were never filled. So
    // start from a form that saves, and change only the tenant.
    await pluginPage.field(/Tenant ID/i).fill('my-tenant');
    await pluginPage.field(/Tenant ID/i).blur();
    await expect(pluginPage.saveButton).toBeEnabled();

    // Uppercase and a space: the slug is 3-63 chars, lowercase with hyphens.
    await pluginPage.field(/Tenant ID/i).fill('INVALID TENANT');
    await pluginPage.field(/Tenant ID/i).blur();
    await expect(pluginPage.saveButton).toBeDisabled();
  });

  test('should enable save when all required fields are filled correctly', async () => {
    await pluginPage.openAddPluginModal();
    await pluginPage.selectEpistolaPlugin();

    await pluginPage.field(/Configuration name|Configuratienaam/i).fill('Test Config');
    await pluginPage.field(/Base URL/i).fill('https://api.epistola.app');
    await pluginPage.field(/API Key/i).fill('test-api-key');
    await pluginPage.field(/Tenant ID/i).fill('my-tenant');
    await pluginPage.field(/Tenant ID/i).blur();

    await expect(pluginPage.saveButton).toBeEnabled();
  });
});
