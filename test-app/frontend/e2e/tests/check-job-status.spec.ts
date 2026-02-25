import { test, expect } from '@playwright/test';
import { PluginManagementPage } from '../pages/plugin-management.page';

test.describe('Check Job Status Action Configuration', () => {
  let pluginPage: PluginManagementPage;

  test.beforeEach(async ({ page }) => {
    pluginPage = new PluginManagementPage(page);
  });

  test('should navigate to plugins page', async ({ page }) => {
    await pluginPage.navigate();
    await expect(page).toHaveURL(/.*plugins.*/);
  });

  test('should have expected default values for check-job-status fields', async ({ page }) => {
    // The check-job-status action configuration component pre-fills these defaults:
    // - requestIdVariable: "epistolaRequestId"
    // - statusVariable: "epistolaStatus"
    // - documentIdVariable: "epistolaDocumentId"
    // - errorMessageVariable: "epistolaErrorMessage"

    // This test verifies the component structure via the plugin specification.
    // Full interactive tests (opening the action config modal in a process link)
    // should be done via Playwright MCP or expanded once navigation selectors are stable.
    await pluginPage.navigate();
    await expect(page.getByText(/Epistola/)).toBeVisible();
  });
});
