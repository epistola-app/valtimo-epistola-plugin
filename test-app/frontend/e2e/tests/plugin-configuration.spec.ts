import { test, expect } from '@playwright/test';
import { PluginManagementPage } from '../pages/plugin-management.page';

test.describe('Epistola Plugin Configuration', () => {
  let pluginPage: PluginManagementPage;

  test.beforeEach(async ({ page }) => {
    pluginPage = new PluginManagementPage(page);
    await pluginPage.navigate();
  });

  test('should show Epistola in the plugin list', async ({ page }) => {
    await pluginPage.openAddPluginModal();
    await expect(page.getByText('Epistola Document Suite')).toBeVisible();
  });

  test('should render all plugin configuration fields', async ({ page }) => {
    await pluginPage.openAddPluginModal();
    await pluginPage.selectEpistolaPlugin();

    // Verify all plugin configuration fields are present
    await expect(page.getByLabel(/Configuration name|Configuratienaam/i)).toBeVisible();
    await expect(page.getByLabel(/Base URL/i)).toBeVisible();
    await expect(page.getByLabel(/API Key/i)).toBeVisible();
    await expect(page.getByLabel(/Tenant ID/i)).toBeVisible();
    await expect(page.getByLabel(/Default Environment|Standaard Omgeving/i)).toBeVisible();
  });

  test('should mask API Key as password field', async ({ page }) => {
    await pluginPage.openAddPluginModal();
    await pluginPage.selectEpistolaPlugin();

    const apiKeyInput = page.getByLabel(/API Key/i);
    await expect(apiKeyInput).toHaveAttribute('type', 'password');
  });

  test('should require mandatory fields before saving', async ({ page }) => {
    await pluginPage.openAddPluginModal();
    await pluginPage.selectEpistolaPlugin();

    // The save button should be disabled when required fields are empty
    const saveButton = page.getByRole('button', { name: /save|opslaan/i });
    await expect(saveButton).toBeDisabled();
  });

  test('should validate tenantId slug format', async ({ page }) => {
    await pluginPage.openAddPluginModal();
    await pluginPage.selectEpistolaPlugin();

    // Fill required fields with valid data except tenantId
    await page.getByLabel(/Configuration name|Configuratienaam/i).fill('Test Config');
    await page.getByLabel(/Base URL/i).fill('https://api.epistola.app');
    await page.getByLabel(/API Key/i).fill('test-api-key');

    // Enter an invalid tenant ID (uppercase, spaces)
    await page.getByLabel(/Tenant ID/i).fill('INVALID TENANT');

    // Save should remain disabled due to validation
    const saveButton = page.getByRole('button', { name: /save|opslaan/i });
    await expect(saveButton).toBeDisabled();
  });

  test('should enable save when all required fields are filled correctly', async ({ page }) => {
    await pluginPage.openAddPluginModal();
    await pluginPage.selectEpistolaPlugin();

    await page.getByLabel(/Configuration name|Configuratienaam/i).fill('Test Config');
    await page.getByLabel(/Base URL/i).fill('https://api.epistola.app');
    await page.getByLabel(/API Key/i).fill('test-api-key');
    await page.getByLabel(/Tenant ID/i).fill('my-tenant');

    // Save should now be enabled
    const saveButton = page.getByRole('button', { name: /save|opslaan/i });
    await expect(saveButton).toBeEnabled();
  });
});
