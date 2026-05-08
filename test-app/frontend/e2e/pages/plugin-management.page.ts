import { type Page, type Locator, expect } from '@playwright/test';

export class PluginManagementPage {
  readonly page: Page;
  readonly addPluginButton: Locator;

  constructor(page: Page) {
    this.page = page;
    this.addPluginButton = page.getByRole('button', {
      name: /add plugin|plugin toevoegen|configure plugin|plugin configureren/i,
    });
  }

  async navigate() {
    await this.page.goto('/plugins');
    // Wait for the plugin list page header instead of networkidle — Angular's
    // HMR websocket keeps the network busy in dev mode, so networkidle never
    // settles within Playwright's timeout.
    await this.page.waitForLoadState('domcontentloaded');
    await expect(this.addPluginButton).toBeVisible({ timeout: 15_000 });
  }

  async openAddPluginModal() {
    await this.addPluginButton.click();
    // Wait for the modal's first step heading. Asserting visibility on the
    // <cds-modal> host element itself is unreliable because Carbon keeps the
    // host at display:none and only the inner panel becomes visible.
    await expect(
      this.page.getByText(/Kies je plugin|Choose your plugin|Select plugin/i).first(),
    ).toBeVisible({ timeout: 10_000 });
  }

  async selectEpistolaPlugin() {
    // Click the Epistola card in the open "Choose plugin" modal. We scope to
    // <h5> headings (the card titles) so we don't accidentally match the
    // sidebar nav item or the existing-config row in the table behind the modal.
    await this.page.getByRole('heading', { level: 5, name: /Epistola Document Suite/i }).click();
    // The new Valtimo UI uses a 2-step wizard ("Kies je plugin" → "Gegevens
    // invullen"). Clicking the card only ticks the radio; advance to the form
    // step to expose the configuration fields. The advance button reuses the
    // step-2 label ("Gegevens invullen" / "Fill in details").
    const nextButton = this.page.getByRole('button', {
      name: /^(next|volgende|gegevens invullen|fill in details)$/i,
    });
    if (await nextButton.isVisible().catch(() => false)) {
      await nextButton.click();
    }
  }

  /**
   * Opens the configuration form for a specific plugin action.
   * Assumes a plugin configuration already exists.
   */
  async openPluginConfiguration(pluginName: string) {
    await this.navigate();
    await this.page.getByText(pluginName, { exact: false }).click();
    await this.page.waitForLoadState('domcontentloaded');
  }
}
