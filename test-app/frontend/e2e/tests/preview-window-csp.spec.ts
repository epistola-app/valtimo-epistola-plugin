// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import { expect, test, type BrowserContext, type Page } from '@playwright/test';
import { createDossier } from '../pages/correspondentie.page';
import { composerField } from '../pages/composer-field';

/**
 * A preview in a window of its own obeys the host's Content-Security-Policy exactly as the preview
 * in the form does — it never gets round it, and needs nothing the form does not.
 *
 * The window is an empty page Valtimo opens, so it inherits Valtimo's policy, and its PDF is an
 * `<object>` under the same `object-src` rule as the form's. Proved here with two policies: one that
 * lets a blob PDF load (both show the letter) and `object-src 'none'` (both stay empty).
 *
 * Two things this depends on, each found by a test that passed for the wrong reason:
 * - **A PDF viewer.** Without one nothing loads the `<object>` and no policy is ever checked, so
 *   "no violations" means nothing. This suite runs the full Chromium build for that reason
 *   (`channel: 'chromium'` in the config); the lightweight headless shell has no viewer.
 * - **The policy on the page the app runs in.** That page arrives through a redirect from Keycloak,
 *   which a route does not intercept, so a header added there never reaches it. The policy is put
 *   in as a `<meta>` in `<head>` before Valtimo's scripts instead, the other way a host can send it.
 *
 * Needs a reachable Epistola, since the preview renders a real PDF.
 */
async function withPolicy(context: BrowserContext, policy: string): Promise<void> {
  await context.addInitScript((csp) => {
    if (location.origin !== 'http://localhost:4200') return;
    const add = (head: HTMLHeadElement) => {
      const meta = document.createElement('meta');
      meta.httpEquiv = 'Content-Security-Policy';
      meta.content = csp;
      head.prepend(meta);
    };
    // Only honoured inside <head>, which does not exist yet when this runs.
    if (document.head) add(document.head);
    else
      new MutationObserver((_, observer) => {
        if (document.head) {
          observer.disconnect();
          add(document.head);
        }
      }).observe(document, { childList: true, subtree: true });
    (window as any).__violations = [];
    document.addEventListener('securitypolicyviolation', (e) =>
      (window as any).__violations.push(e.violatedDirective),
    );
  }, policy);
}

/**
 * Whether a page shows a letter rather than nothing: a PDF viewer offers no DOM to ask, so its
 * screenshot is decoded by the browser itself and its colours counted.
 *
 * Measured: a rendered letter has ~860 colours in the form and ~2200 in the window (the viewer's
 * toolbar adds some); a refused one has ~140 in the form, where the `<object>`'s anti-aliased
 * fallback text shows, and 3 in the empty window. 400 sits well clear of both.
 */
const LETTER_COLOURS = 400;

async function showsALetter(page: Page, shot: Buffer): Promise<boolean> {
  const colours = await page.evaluate(async (png) => {
    const img = new Image();
    img.src = `data:image/png;base64,${png}`;
    await img.decode();
    const canvas = document.createElement('canvas');
    canvas.width = img.width;
    canvas.height = img.height;
    const ctx = canvas.getContext('2d')!;
    ctx.drawImage(img, 0, 0);
    const { data } = ctx.getImageData(0, 0, img.width, img.height);
    const seen = new Set<number>();
    for (let i = 0; i < data.length; i += 4 * 7) {
      seen.add((data[i] << 16) | (data[i + 1] << 8) | data[i + 2]);
    }
    return seen.size;
  }, shot.toString('base64'));
  return colours > LETTER_COLOURS;
}

async function letterInFormAndWindow(page: Page) {
  await createDossier(page);
  await page.getByText('Kies een brief').first().click({ timeout: 20_000 });
  await page.getByTestId('epistola-composer-select').selectOption('besluit-bezwaar');
  const inputs = page.getByTestId('epistola-composer-inputs');
  await composerField(inputs, 'decisionType').fill('gegrond');
  await composerField(inputs, 'decision').fill('Het bezwaar is gegrond verklaard.');
  await composerField(inputs, 'motivation').fill('De dakkapel voldoet.');
  const formLetter = page.getByTestId('epistola-composer-preview-pdf');
  await expect(formLetter).toBeVisible({ timeout: 60_000 });
  await page.waitForTimeout(2_500);
  const inForm = await showsALetter(page, await formLetter.screenshot());
  const violations: string[] = await page.evaluate(() => (window as any).__violations);

  const [popup] = await Promise.all([
    page.waitForEvent('popup'),
    page.getByTestId('epistola-composer-preview-window').click(),
  ]);
  await expect.poll(() => popup.locator('object').count(), { timeout: 15_000 }).toBe(1);
  await popup.waitForTimeout(3_000);
  const inWindow = await showsALetter(page, await popup.screenshot());
  return { inForm, inWindow, violations };
}

test.describe('Previews in a window of their own — under a Content-Security-Policy', () => {
  test.setTimeout(180_000);

  test('show the letter where the policy lets the form show it', async ({ page, context }) => {
    await withPolicy(context, "object-src 'self' blob:");

    const { inForm, inWindow, violations } = await letterInFormAndWindow(page);

    expect(violations).toEqual([]);
    expect(inForm).toBe(true);
    expect(inWindow).toBe(true);
  });

  test('stay empty where the policy keeps the form empty too', async ({ page, context }) => {
    await withPolicy(context, "object-src 'none'");

    const { inForm, inWindow, violations } = await letterInFormAndWindow(page);

    // The policy is in force — it refused the form's letter — and the window follows it.
    expect(violations).toContain('object-src');
    expect(inForm).toBe(false);
    expect(inWindow).toBe(false);
  });
});
