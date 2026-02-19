import { test, expect } from '@playwright/test';
import { PluginManagementPage } from '../pages/plugin-management.page';

test.describe('Download Document Action Configuration', () => {
  let pluginPage: PluginManagementPage;

  test.beforeEach(async ({ page }) => {
    pluginPage = new PluginManagementPage(page);
  });

  test('should navigate to plugins page', async ({ page }) => {
    await pluginPage.navigate();
    await expect(page).toHaveURL(/.*plugins.*/);
  });

  test('should show Epistola plugin on plugins page', async ({ page }) => {
    // The download-document action configuration component pre-fills:
    // - documentIdVariable: "epistolaDocumentId"
    // - contentVariable: "documentContent"

    // This test verifies the plugin is accessible via the plugins page.
    // Full interactive action config tests require process link navigation
    // which needs Playwright MCP discovery of exact selectors.
    await pluginPage.navigate();
    await expect(page.getByText(/Epistola/)).toBeVisible();
  });
});
