// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import type { Locator } from '@playwright/test';

/**
 * Click a control that is out of view while a rendered PDF is on the page — a dialog's submit button
 * below a composer's preview, typically.
 *
 * A PDF shows in a frame of its own, and after a scroll that frame's place on screen catches up a
 * moment later. Playwright scrolls a control into view and clicks in the same instant, so the click
 * is routed by the PDF's old place, lands in the viewer, and never reaches the button (measured: no
 * click event on the page, focus moves into the viewer; scrolling first and waiting a moment, the
 * same click submits). A person never clicks within milliseconds of a scroll, so this is the test's
 * problem, not the employee's — which is why it is solved here and not in the component.
 */
export async function clickSettled(target: Locator, options?: { force?: boolean }): Promise<void> {
  await target.scrollIntoViewIfNeeded();
  await target.page().waitForTimeout(500);
  await target.click(options);
}
