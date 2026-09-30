// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import { type Page, type Locator, expect } from '@playwright/test';

/**
 * Valtimo's plugin-management screen and its two-step "configure plugin" wizard.
 *
 * Two things about this UI defeat the obvious selectors, and both cost the whole suite when
 * assumed away:
 *
 * - **Carbon keeps the entire wizard mounted.** Every step, and the delete and upload dialogs,
 *   are in the DOM from the moment the page loads. So "is the modal open?" cannot be answered by
 *   presence, and a bare `getByText('Epistola Document Suite')` matches three elements — the tile
 *   heading, a description span and the tile's own label — and fails Playwright's strict mode.
 * - **`getByLabel` does not work here at all.** Valtimo wraps Carbon's `<cds-label>`, whose
 *   `for` points at the label's own generated id rather than at the control, and the `<input>` is
 *   not a descendant of the label — it is a sibling further up, inside `div.v-input-container`.
 *   So every `getByLabel(/Base URL/)` resolves to zero elements. Fields are reached by filtering
 *   that container on its label text instead; see {@link field}.
 *
 * Both were re-derived against the running app rather than guessed.
 */
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
    // Not networkidle: Angular's dev-server websocket keeps the network busy, so it never settles.
    await this.page.waitForLoadState('domcontentloaded');
    await expect(this.addPluginButton).toBeVisible({ timeout: 15_000 });
  }

  /**
   * Open the wizard and wait until its first step is actually on screen.
   *
   * Waiting on the step *heading* would pass before the dialog opens, since Carbon has it mounted
   * all along. The tiles only become visible once it does, so they are the signal.
   */
  async openAddPluginModal() {
    await this.addPluginButton.click();
    await expect(this.tile('Epistola Document Suite')).toBeVisible({ timeout: 10_000 });
  }

  /** The selectable tile for a plugin: its `<label>`, which is what drives the radio. */
  tile(pluginName: string): Locator {
    return this.page.locator('label').filter({ hasText: pluginName }).first();
  }

  /**
   * Pick Epistola and advance to the details step.
   *
   * The tile is a radio driven by its label, so the label is what gets clicked — clicking the
   * heading text lands on a `<span>` inside it, selects nothing, and leaves the wizard unable to
   * advance, which then reads as "the configuration fields are missing".
   */
  async selectEpistolaPlugin() {
    const tile = this.tile('Epistola Document Suite');
    await tile.click();

    const radioId = await tile.getAttribute('for');
    if (radioId) {
      await expect(this.page.locator(`#${radioId}`)).toBeChecked();
    }

    await this.page
      .getByRole('button', { name: /^(next|volgende|gegevens invullen|fill in details)$/i })
      .first()
      .click();

    await expect(this.field(/Base URL/i)).toBeVisible({ timeout: 10_000 });
  }

  /**
   * A configuration field, by the text of its label.
   *
   * `getByLabel` cannot find these (see the class note), so this goes the other way: find the
   * field wrapper that contains the label text, then the control inside it.
   */
  field(label: string | RegExp): Locator {
    return this.page
      .locator('.v-input-container')
      .filter({ hasText: label })
      .locator('input, textarea')
      .first();
  }

  /** The wizard's save button. */
  get saveButton(): Locator {
    return this.page
      .getByRole('button', { name: /^(save|opslaan|configuratie opslaan)$/i })
      .first();
  }

  /**
   * Opens the configuration form for a specific plugin action.
   * Assumes a plugin configuration already exists.
   */
  async openPluginConfiguration(pluginName: string) {
    await this.navigate();
    await this.page.getByText(pluginName, { exact: false }).first().click();
    await this.page.waitForLoadState('domcontentloaded');
  }
}
