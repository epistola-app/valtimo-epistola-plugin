// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import { test, expect } from '@playwright/test';
import { createDossier } from '../pages/correspondentie.page';
import { composerField } from '../pages/composer-field';

/**
 * The composer's preview inside a real Valtimo dialog: popped out, moved, docked, and paused.
 *
 * Worth a browser test rather than only unit tests because popping out moves the preview's element
 * out of the dialog onto the page. Whether a click on it then closes the dialog, whether the PDF
 * still updates from a moved element, and whether closing the dialog leaves the panel behind are
 * questions about Valtimo's dialog and Angular together, which no unit test can answer.
 *
 * Needs a reachable Epistola, since the preview renders a real PDF.
 */
test.describe('Letter composer — the preview panel', () => {
  test('pops out of the dialog, keeps updating, docks, and pauses on request', async ({ page }) => {
    test.setTimeout(180_000);
    await page.setViewportSize({ width: 1280, height: 720 });

    await createDossier(page);
    await page.getByText('Kies een brief').first().click({ timeout: 20_000 });
    await page.getByTestId('epistola-composer-select').selectOption('besluit-bezwaar');
    const inputs = page.getByTestId('epistola-composer-inputs');
    await composerField(inputs, 'decisionType').fill('gegrond');
    await composerField(inputs, 'decision').fill('Het bezwaar is gegrond verklaard.');
    const motivation = composerField(inputs, 'motivation');
    await motivation.fill('Eerste versie.');
    await expect(page.getByTestId('epistola-composer-preview-pdf')).toBeVisible({
      timeout: 60_000,
    });

    // Popped out: onto the page itself, so a dialog's transform cannot hold it inside.
    const pane = page.getByTestId('epistola-composer-preview');
    await page.getByTestId('epistola-composer-popout').click();
    expect(await pane.evaluate((el) => el.parentElement === document.body)).toBe(true);

    // A click on the panel is not a click outside the dialog.
    const header = page.getByTestId('epistola-composer-preview-header');
    await header.click({ position: { x: 20, y: 8 } });
    await expect(page.getByTestId('epistola-composer-select')).toBeVisible();

    // Dragged by its header.
    const before = (await pane.boundingBox())!;
    const grip = (await header.boundingBox())!;
    await page.mouse.move(grip.x + 20, grip.y + 8);
    await page.mouse.down();
    await page.mouse.move(grip.x - 280, grip.y + 8, { steps: 5 });
    await page.mouse.up();
    expect(Math.round((await pane.boundingBox())!.x - before.x)).toBe(-300);

    // Still the composer's preview: typing renders into the floating panel.
    const renders: string[] = [];
    page.on('request', (request) => {
      if (request.url().includes('/composer/preview')) renders.push(request.url());
    });
    await motivation.fill('Tweede versie.');
    await expect.poll(() => renders.length, { timeout: 15_000 }).toBeGreaterThan(0);
    await expect(page.getByTestId('epistola-composer-preview-pdf')).toBeVisible({
      timeout: 60_000,
    });

    // Paused: nothing renders while typing, and the panel says it is behind until Refresh.
    await page.getByTestId('epistola-composer-autorefresh').uncheck();
    renders.length = 0;
    await motivation.fill('Derde versie.');
    await expect(page.getByTestId('epistola-composer-preview-stale')).toBeVisible();
    await page.waitForTimeout(2_000);
    expect(renders).toEqual([]);
    await page.getByTestId('epistola-composer-refresh').click();
    await expect.poll(() => renders.length, { timeout: 15_000 }).toBe(1);
    await expect(page.getByTestId('epistola-composer-preview-stale')).toBeHidden();

    // Docked: back beside the form, inside the composer.
    await page.getByTestId('epistola-composer-popout').click();
    expect(await pane.evaluate((el) => !!el.closest('[data-testid="epistola-composer"]'))).toBe(
      true,
    );

    // Floating when the dialog closes: the panel goes with it rather than staying on the page.
    await page.getByTestId('epistola-composer-popout').click();
    await page.keyboard.press('Escape');
    await expect(page.getByTestId('epistola-composer-preview')).toHaveCount(0, {
      timeout: 10_000,
    });
  });
});
