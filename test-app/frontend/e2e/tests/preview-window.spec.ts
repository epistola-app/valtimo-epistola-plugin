// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import { expect, test } from '@playwright/test';
import { createDossier } from '../pages/correspondentie.page';
import { composerField } from '../pages/composer-field';

/**
 * A preview in a browser window of its own, for a second screen.
 *
 * Walked with the composer, whose frame every preview shares. What only a real browser shows: that
 * the page may open the window at all, that the letter the page rendered shows in it and follows
 * each edit, that closing it brings the letter back to the form, and that the window does not
 * outlive the dialog it was opened from.
 *
 * Needs a reachable Epistola, since the preview renders a real PDF.
 */
test.describe('Previews — in a window of their own', () => {
  test('opens the letter in one window, keeps it current, and closes it with the dialog', async ({
    page,
  }) => {
    test.setTimeout(180_000);

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

    // Opened from the click, holding the letter the page rendered, with the viewer's toolbar.
    const [popup] = await Promise.all([
      page.waitForEvent('popup'),
      page.getByTestId('epistola-composer-preview-window').click(),
    ]);
    const shown = () => popup.locator('object').getAttribute('data');
    await expect.poll(shown).toMatch(/^blob:.*#view=FitH$/);
    await expect(page.getByTestId('epistola-composer-preview-windowed')).toBeVisible();
    await expect(page.getByTestId('epistola-composer-preview-pdf')).toBeHidden();

    // It follows each edit.
    const first = await shown();
    await motivation.fill('Tweede versie.');
    await expect.poll(shown, { timeout: 30_000 }).not.toBe(first);

    // Closed by the employee: the letter is back in the form.
    await popup.close();
    await expect(page.getByTestId('epistola-composer-preview-windowed')).toBeHidden({
      timeout: 5_000,
    });
    await expect(page.getByTestId('epistola-composer-preview-pdf')).toBeVisible();

    // Opened again, then the dialog closes: the window goes with it.
    const [again] = await Promise.all([
      page.waitForEvent('popup'),
      page.getByTestId('epistola-composer-preview-window').click(),
    ]);
    await page.keyboard.press('Escape');
    await expect.poll(() => again.isClosed(), { timeout: 10_000 }).toBe(true);
  });
});
