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

import { safeStorage } from './floating-panel';

/** Where the window was, in screen pixels, so it opens there again. */
export interface WindowGeometry {
  left: number;
  top: number;
  width: number;
  height: number;
}

/**
 * The name every preview window shares. Opening a window by a name that is already open reuses it,
 * so there is never more than one Epistola preview window: a second preview replaces what the
 * first showed instead of opening another.
 */
export const PREVIEW_WINDOW_NAME = 'epistola-preview';

const GEOMETRY_KEY = 'epistola.previewWindow';

/** How often to look whether the employee closed the window themselves. */
const CLOSED_CHECK_MS = 500;

/**
 * A preview shown in a browser window of its own, beside the form, for a second screen.
 *
 * <p>The window holds nothing but the letter: an empty page of the application's own origin with
 * the PDF in it. The PDF is the object URL the page already rendered, so the window needs no
 * Angular, no login and no request of its own, and every new render is swapped in from here.
 *
 * <p>It never outlives what opened it: {@link close} when the form goes, and the page closes it
 * itself when it unloads (a reload, leaving Valtimo, closing the tab). Only a crash of the page
 * leaves it open — nothing runs then.
 */
export class PreviewWindow {
  private win: Window | null = null;
  private closedCheck: ReturnType<typeof setInterval> | null = null;
  private readonly unloading = () => this.close();

  constructor(
    /** Told when the window goes without {@link close}: the employee closed it. */
    private readonly onClosedByUser: () => void,
    private readonly opener: Window = globalThis.window,
    private readonly storage: Storage | undefined = safeStorage(),
  ) {}

  /** Whether the window is open. */
  get open(): boolean {
    return !!this.win && !this.win.closed;
  }

  /**
   * Open the window, or bring the one already open forward. Must run from a click: a browser only
   * lets a page open a window in answer to one.
   *
   * @returns false when the browser blocked it
   */
  show(title: string, url: string | null, waiting = '…'): boolean {
    if (!this.open) {
      const win = this.opener.open('', PREVIEW_WINDOW_NAME, features(this.geometry()));
      if (!win) {
        return false;
      }
      this.win = win;
      this.prepare(win, title, waiting);
      this.opener.addEventListener('pagehide', this.unloading);
      this.closedCheck = setInterval(() => {
        if (this.win?.closed) {
          this.forget();
          this.onClosedByUser();
        }
      }, CLOSED_CHECK_MS);
    }
    this.update(url, title);
    this.win?.focus();
    return true;
  }

  /** Show another rendering of the letter, if the window is open. */
  update(url: string | null, title?: string): void {
    if (!this.open || !this.win) {
      return;
    }
    const doc = this.win.document;
    if (title) {
      doc.title = title;
    }
    const letter = doc.getElementById('letter');
    const message = doc.getElementById('message');
    if (!letter || !message) {
      return;
    }
    // A new element rather than a new `data`: an <object> does not reliably reload in place.
    letter.replaceChildren();
    message.hidden = !!url;
    if (url) {
      const object = doc.createElement('object');
      object.type = 'application/pdf';
      object.data = url;
      object.style.cssText = 'display:block;width:100%;height:100%;border:0';
      letter.appendChild(object);
    }
  }

  /** Close the window, remembering where it was. */
  close(): void {
    if (this.open && this.win) {
      this.remember(this.win);
      this.win.close();
    }
    this.forget();
  }

  private forget(): void {
    if (this.closedCheck) {
      clearInterval(this.closedCheck);
      this.closedCheck = null;
    }
    this.opener.removeEventListener('pagehide', this.unloading);
    this.win = null;
  }

  /** The window's page: the letter filling it, and a line for while there is none. */
  private prepare(win: Window, title: string, waiting: string): void {
    const doc = win.document;
    doc.title = title;
    doc.body.replaceChildren();
    doc.body.style.cssText = 'margin:0;height:100vh;font-family:sans-serif;background:#f4f4f4';
    const letter = doc.createElement('div');
    letter.id = 'letter';
    letter.style.cssText = 'height:100%';
    const message = doc.createElement('p');
    message.id = 'message';
    message.style.cssText = 'margin:1rem;color:#525252';
    message.textContent = waiting;
    doc.body.append(message, letter);
  }

  private remember(win: Window): void {
    try {
      this.storage?.setItem(
        GEOMETRY_KEY,
        JSON.stringify({
          left: win.screenX,
          top: win.screenY,
          width: win.outerWidth,
          height: win.outerHeight,
        }),
      );
    } catch {
      // Not remembered: it opens at the browser's default place next time.
    }
  }

  private geometry(): WindowGeometry | null {
    try {
      const g = JSON.parse(this.storage?.getItem(GEOMETRY_KEY) ?? 'null');
      return g && ['left', 'top', 'width', 'height'].every((k) => Number.isFinite(g[k])) ? g : null;
    } catch {
      return null;
    }
  }
}

/** `window.open` features: a plain popup, where it was last, or a page-sized one. */
export function features(geometry: WindowGeometry | null): string {
  const g = geometry ?? { width: 720, height: 960 };
  const parts = ['popup', `width=${Math.round(g.width)}`, `height=${Math.round(g.height)}`];
  if (geometry) {
    parts.push(`left=${Math.round(geometry.left)}`, `top=${Math.round(geometry.top)}`);
  }
  return parts.join(',');
}
