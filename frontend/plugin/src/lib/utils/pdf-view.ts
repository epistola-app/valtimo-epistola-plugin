/*
 * Copyright 2025 Epistola.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: EUPL-1.2
 */

/**
 * PDF open parameters asking the browser's viewer to show the letter and nothing else: no toolbar,
 * no thumbnail panel, the page fitted to the width.
 *
 * Honoured by the viewer in Chrome and Edge. Firefox's keeps its toolbar but takes the fit, and
 * Safari's ignores them all — each then shows the letter exactly as before, so asking costs nothing
 * where it is not heard. Zooming with the keyboard or mouse still works everywhere.
 */
export const PDF_VIEW_PARAMETERS = 'toolbar=0&navpanes=0&view=FitH';

/**
 * The URL to show a PDF at, with {@link PDF_VIEW_PARAMETERS}.
 *
 * Only for showing it: an object URL is revoked by its own value, without the fragment, so keep the
 * plain URL for that.
 */
export function pdfViewUrl(url: string): string {
  return `${url.split('#')[0]}#${PDF_VIEW_PARAMETERS}`;
}

/**
 * The URL to show a PDF at in a window of its own: fitted to the width, with the viewer's toolbar.
 * There is room for it there, and printing or zooming is what a separate window is often for.
 */
export function windowViewUrl(url: string): string {
  return `${url.split('#')[0]}#view=FitH`;
}
