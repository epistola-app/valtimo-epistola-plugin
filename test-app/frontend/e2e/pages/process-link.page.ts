// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import { type Page, type Locator, expect } from '@playwright/test';

/**
 * Valtimo's process-link authoring screen (`/process-links`), where a BPMN activity is bound to a
 * plugin action.
 *
 * This is the only place the plugin's **action configurators** render, so it is the only place a
 * browser can check them. Three suites previously stood in for it with tests that navigated to
 * `/plugins` and asserted a URL, under names claiming they rendered the configuration fields.
 *
 * Two things make this drivable at all:
 *
 * - **Activities are addressed by their BPMN id.** The diagram is rendered by bpmn-js, which puts
 *   `data-element-id` on every shape, so `st-gen-a` or `download-ack` selects exactly the activity
 *   the fixture declares — no clicking at coordinates, and a renamed activity fails loudly.
 * - **The configurators carry `data-testid` throughout.** Their fields are reached by id rather
 *   than by label, which matters because Valtimo's form labels are not associated with their
 *   controls at all (see `PluginManagementPage` for what that breaks).
 *
 * An activity that already has a link opens its configurator directly. An unlinked one opens a
 * four-step wizard instead — {@link chooseEpistolaAction} walks it.
 */
export class ProcessLinkPage {
  constructor(private readonly page: Page) {}

  /** Open the screen and select a process definition by the name shown in its dropdown. */
  async openProcess(processLabel: string): Promise<void> {
    await this.page.goto('/process-links');
    await this.page.waitForLoadState('domcontentloaded');

    const processSelect = this.page.locator('select').first();
    await expect(processSelect).toBeVisible({ timeout: 20_000 });
    await processSelect.selectOption({ label: processLabel });

    // The diagram replaces itself when the process changes; its shapes are the signal it arrived.
    await expect(this.page.locator('[data-element-id]').first()).toBeVisible({ timeout: 20_000 });
  }

  /**
   * Click an activity by its BPMN id and wait for the configuration panel.
   *
   * `force` because bpmn-js draws overlapping shapes (labels, outlines) over the hit area, and
   * Playwright's actionability check reports the outline as the receiver.
   */
  async openActivity(elementId: string): Promise<void> {
    await this.page.locator(`[data-element-id="${elementId}"]`).first().click({ force: true });
    await expect(
      this.page.getByRole('heading', { name: /Processtap:|Process step:/i }),
    ).toBeVisible({
      timeout: 15_000,
    });
  }

  /**
   * A configurator field's control, by the `data-testid` the component carries.
   *
   * Some components put the id on the input and others on a wrapper around it, so both shapes are
   * accepted rather than encoded per field.
   */
  control(testId: string): Locator {
    return this.page
      .locator(
        `[data-testid="${testId}"] input, [data-testid="${testId}"] textarea, input[data-testid="${testId}"]`,
      )
      .first();
  }

  /** A configurator region (the form itself, the mapping builder, a toggle group). */
  region(testId: string): Locator {
    return this.page.getByTestId(testId);
  }

  /**
   * Walk the new-link wizard on an **unlinked** activity up to a chosen Epistola action.
   *
   * The steps are: what kind of link ("Plugins & Apps"), which plugin configuration (a table with
   * a row per configuration), which action, then the action's own form.
   */
  async chooseEpistolaAction(actionLabel: string | RegExp): Promise<void> {
    await this.page
      .getByRole('button', { name: /Plugins & Apps/i })
      .first()
      .click();

    const configRow = this.page
      .locator('cds-list-row')
      .filter({ hasText: 'Epistola Document Suite' })
      .first();
    await expect(configRow).toBeVisible({ timeout: 15_000 });
    await configRow.click();
    await this.next();

    const actionRow = this.page.locator('cds-list-row').filter({ hasText: actionLabel }).first();
    await expect(actionRow).toBeVisible({ timeout: 15_000 });
    await actionRow.click();
    await this.next();
  }

  private async next(): Promise<void> {
    await this.page
      .getByRole('button', { name: /^(volgende|next)$/i })
      .first()
      .click();
  }
}
