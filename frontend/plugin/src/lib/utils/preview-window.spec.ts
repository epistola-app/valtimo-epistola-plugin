/**
 * @jest-environment jsdom
 */
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

import { features, PREVIEW_WINDOW_NAME, PreviewWindow } from './preview-window';

describe('PreviewWindow', () => {
  /** A popup as the browser hands it back: its own document, closable. */
  const popup = () => ({
    document: document.implementation.createHTMLDocument('popup'),
    closed: false,
    close() {
      this.closed = true;
    },
    focus: jest.fn(),
    screenX: 1920,
    screenY: 40,
    outerWidth: 800,
    outerHeight: 1000,
  });

  /** The page that opens it: records how it was asked, and can unload. */
  const opener = (opened: ReturnType<typeof popup> | null = popup()) => {
    const events = new EventTarget();
    return Object.assign(events, {
      open: jest.fn(() => opened),
      opened,
    }) as unknown as Window & { open: jest.Mock; opened: ReturnType<typeof popup> };
  };

  const memory = () => {
    const items = new Map<string, string>();
    return {
      getItem: (key: string) => items.get(key) ?? null,
      setItem: (key: string, value: string) => void items.set(key, value),
    } as unknown as Storage;
  };

  const shown = (page: { opened: ReturnType<typeof popup> }) =>
    page.opened.document.querySelector('object')?.getAttribute('data') ?? null;

  beforeEach(() => jest.useFakeTimers());
  afterEach(() => jest.useRealTimers());

  it('opens one named window holding only the letter', () => {
    const page = opener();
    const win = new PreviewWindow(() => {}, page, memory());

    expect(win.show('Besluit — Epistola', 'blob:x/1#view=FitH')).toBe(true);

    expect(page.open).toHaveBeenCalledWith(
      '',
      PREVIEW_WINDOW_NAME,
      expect.stringContaining('popup'),
    );
    expect(page.opened.document.title).toBe('Besluit — Epistola');
    expect(shown(page)).toBe('blob:x/1#view=FitH');
    expect(win.open).toBe(true);
  });

  it('swaps in each new rendering, and says it is waiting while there is none', () => {
    const page = opener();
    const win = new PreviewWindow(() => {}, page, memory());
    win.show('Besluit', null, 'Even wachten');
    expect(page.opened.document.getElementById('message')?.textContent).toBe('Even wachten');
    expect(shown(page)).toBeNull();

    win.update('blob:x/2');
    expect(shown(page)).toBe('blob:x/2');
    expect(page.opened.document.getElementById('message')?.hidden).toBe(true);
  });

  it('opens it only once: showing again brings the same window forward', () => {
    const page = opener();
    const win = new PreviewWindow(() => {}, page, memory());

    win.show('Besluit', 'blob:x/1');
    win.show('Besluit', 'blob:x/2');

    expect(page.open).toHaveBeenCalledTimes(1);
    expect(page.opened.focus).toHaveBeenCalledTimes(2);
    expect(shown(page)).toBe('blob:x/2');
  });

  it('says so when the browser blocks the window', () => {
    const win = new PreviewWindow(() => {}, opener(null), memory());

    expect(win.show('Besluit', 'blob:x/1')).toBe(false);
    expect(win.open).toBe(false);
  });

  it('closes when told, and opens where it was the next time', () => {
    const store = memory();
    const page = opener();
    const win = new PreviewWindow(() => {}, page, store);
    win.show('Besluit', 'blob:x/1');

    win.close();
    expect(page.opened.closed).toBe(true);
    expect(win.open).toBe(false);

    const next = opener();
    new PreviewWindow(() => {}, next, store).show('Besluit', 'blob:x/1');
    expect(next.open.mock.calls[0][2]).toBe('popup,width=800,height=1000,left=1920,top=40');
  });

  it('notices when the employee closes it, so the letter can come back to the form', () => {
    const closedByUser = jest.fn();
    const page = opener();
    const win = new PreviewWindow(closedByUser, page, memory());
    win.show('Besluit', 'blob:x/1');

    page.opened.closed = true;
    jest.advanceTimersByTime(600);

    expect(closedByUser).toHaveBeenCalledTimes(1);
    expect(win.open).toBe(false);
  });

  it('closes with the page, so a reload or leaving Valtimo leaves no window behind', () => {
    const page = opener();
    new PreviewWindow(() => {}, page, memory()).show('Besluit', 'blob:x/1');

    page.dispatchEvent(new Event('pagehide'));

    expect(page.opened.closed).toBe(true);
  });

  it('asks for a page-sized popup when nothing was remembered', () => {
    expect(features(null)).toBe('popup,width=720,height=960');
  });
});
