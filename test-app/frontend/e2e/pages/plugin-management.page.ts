import { type Page, type Locator, expect } from '@playwright/test';

export class PluginManagementPage {
  readonly page: Page;
  readonly addPluginButton: Locator;

  constructor(page: Page) {
    this.page = page;
    this.addPluginButton = page.getByRole('button', { name: /add plugin|plugin toevoegen/i });
  }

  async navigate() {
    await this.page.goto('/plugins');
    await this.page.waitForLoadState('networkidle');
  }

  async openAddPluginModal() {
    await this.addPluginButton.click();
    // Wait for the modal to appear
    await expect(this.page.locator('.modal, cds-modal, valtimo-modal')).toBeVisible({ timeout: 5_000 });
  }

  async selectEpistolaPlugin() {
    // Click on the Epistola plugin card/entry in the plugin list
    await this.page.getByText('Epistola', { exact: false }).click();
  }

  /**
   * Opens the configuration form for a specific plugin action.
   * Assumes a plugin configuration already exists.
   */
  async openPluginConfiguration(pluginName: string) {
    await this.navigate();
    await this.page.getByText(pluginName, { exact: false }).click();
    await this.page.waitForLoadState('networkidle');
  }
}
