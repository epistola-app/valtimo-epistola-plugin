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

import {
  clampDockedHeight,
  clampDockedWidth,
  clampGeometry,
  defaultGeometry,
  FloatingPanel,
  MIN_PANEL,
  PopOut,
  readPanelMemory,
  writePanelMemory,
} from './floating-panel';

const LAPTOP = { width: 1280, height: 720 };

describe('panel geometry', () => {
  it('starts along the right edge, most of the screen high, about a page wide', () => {
    const g = defaultGeometry(LAPTOP);

    expect(g.height).toBe(612);
    expect(g.width).toBe(459);
    expect(g.left + g.width).toBe(LAPTOP.width - 16);
    expect(g.top).toBeGreaterThanOrEqual(0);
  });

  it('pulls a panel back on screen, so its header can always be reached', () => {
    expect(clampGeometry({ left: 1200, top: -50, width: 500, height: 400 }, LAPTOP)).toEqual({
      left: 780,
      top: 0,
      width: 500,
      height: 400,
    });
  });

  it('shrinks one remembered from a larger screen, but never below a usable size', () => {
    const g = clampGeometry({ left: 0, top: 0, width: 3000, height: 50 }, LAPTOP);

    expect(g.width).toBe(LAPTOP.width);
    expect(g.height).toBe(MIN_PANEL.height);
  });
});

describe('docked size', () => {
  it('keeps the docked preview between a quarter and three quarters of the width', () => {
    expect(clampDockedWidth(0.1)).toBe(0.25);
    expect(clampDockedWidth(0.6)).toBe(0.6);
    expect(clampDockedWidth(0.9)).toBe(0.75);
  });

  it('keeps its height between a usable page and the screen', () => {
    expect(clampDockedHeight(100, 720)).toBe(MIN_PANEL.height);
    expect(clampDockedHeight(500.4, 720)).toBe(500);
    expect(clampDockedHeight(2000, 720)).toBe(720);
  });
});

describe('panel memory', () => {
  const storage = () => {
    const items = new Map<string, string>();
    return {
      getItem: (key: string) => items.get(key) ?? null,
      setItem: (key: string, value: string) => void items.set(key, value),
    };
  };

  it('remembers whether the panel floats, and where', () => {
    const store = storage();
    const geometry = { left: 10, top: 20, width: 400, height: 500 };

    writePanelMemory({ floating: true, geometry }, store);

    expect(readPanelMemory(store)).toEqual({ floating: true, geometry });
  });

  it('remembers how the docked preview was sized, within its limits', () => {
    const store = storage();
    store.setItem(
      'epistola.composer.previewPanel',
      JSON.stringify({ floating: false, dockedWidth: 0.95, dockedHeight: 640 }),
    );

    expect(readPanelMemory(store)).toEqual({
      floating: false,
      geometry: undefined,
      dockedWidth: 0.75,
      dockedHeight: 640,
    });
  });

  it('reads anything it cannot use as docked, without failing', () => {
    const broken = { getItem: () => '{not json' };
    const throwing = {
      getItem: () => {
        throw new Error('SecurityError');
      },
    };

    expect(readPanelMemory(broken)).toEqual({ floating: false });
    expect(readPanelMemory(throwing)).toEqual({ floating: false });
    expect(readPanelMemory(undefined)).toEqual({ floating: false });
  });

  it('drops a remembered place that is not one', () => {
    const store = storage();
    store.setItem(
      'epistola.composer.previewPanel',
      JSON.stringify({ floating: true, geometry: { left: 'x' } }),
    );

    expect(readPanelMemory(store)).toEqual({ floating: true, geometry: undefined });
  });
});

describe('FloatingPanel', () => {
  let host: HTMLElement;
  let pane: HTMLElement;
  let header: HTMLElement;
  let resizer: HTMLElement;
  let button: HTMLButtonElement;

  beforeEach(() => {
    document.body.innerHTML = '';
    Object.assign(window, { innerWidth: LAPTOP.width, innerHeight: LAPTOP.height });
    host = document.createElement('div');
    host.className = 'dialog';
    pane = document.createElement('div');
    header = document.createElement('div');
    button = document.createElement('button');
    resizer = document.createElement('div');
    header.appendChild(button);
    pane.append(header, resizer);
    host.appendChild(pane);
    document.body.appendChild(host);
  });

  const pointer = (type: string, target: EventTarget, x: number, y: number) => {
    const event = new MouseEvent(type, { clientX: x, clientY: y, bubbles: true });
    target.dispatchEvent(event);
  };

  it('moves out of the dialog onto the page, fixed where it was remembered', () => {
    new FloatingPanel(
      pane,
      header,
      resizer,
      { left: 100, top: 50, width: 400, height: 500 },
      () => {},
    );

    expect(pane.parentElement).toBe(document.body);
    expect(host.contains(pane)).toBe(false);
    expect(pane.style.position).toBe('fixed');
    expect(pane.style.left).toBe('100px');
    expect(Number(pane.style.zIndex)).toBeGreaterThan(9000);
  });

  it('goes back exactly where Angular put it when docked', () => {
    const sibling = document.createElement('span');
    host.appendChild(sibling);
    const panel = new FloatingPanel(pane, header, resizer, undefined, () => {});

    panel.release();

    expect(pane.parentElement).toBe(host);
    expect(pane.nextSibling).toBe(sibling);
    expect(pane.style.position).toBe('');
  });

  it('leaves the page when the dialog it came from is gone', () => {
    const panel = new FloatingPanel(pane, header, resizer, undefined, () => {});
    host.remove();

    panel.release();

    expect(document.body.contains(pane)).toBe(false);
  });

  it('is dragged by its header and remembers where it was dropped', () => {
    const moved = jest.fn();
    new FloatingPanel(
      pane,
      header,
      resizer,
      { left: 100, top: 50, width: 400, height: 500 },
      moved,
    );

    pointer('pointerdown', header, 10, 10);
    expect(pane.classList.contains('dragging')).toBe(true);
    pointer('pointermove', window, 60, 30);
    pointer('pointerup', window, 60, 30);

    expect(pane.style.left).toBe('150px');
    expect(pane.style.top).toBe('70px');
    expect(pane.classList.contains('dragging')).toBe(false);
    expect(moved).toHaveBeenCalledWith({ left: 150, top: 70, width: 400, height: 500 });
  });

  it('is resized from its corner', () => {
    new FloatingPanel(
      pane,
      header,
      resizer,
      { left: 100, top: 50, width: 400, height: 500 },
      () => {},
    );

    pointer('pointerdown', resizer, 500, 550);
    pointer('pointermove', window, 560, 600);
    pointer('pointerup', window, 560, 600);

    expect(pane.style.width).toBe('460px');
    expect(pane.style.height).toBe('550px');
  });

  it('leaves a press on a header button to the button', () => {
    new FloatingPanel(
      pane,
      header,
      resizer,
      { left: 100, top: 50, width: 400, height: 500 },
      () => {},
    );

    pointer('pointerdown', button, 10, 10);
    pointer('pointermove', window, 200, 200);

    expect(pane.style.left).toBe('100px');
  });

  it('stops listening once docked', () => {
    const panel = new FloatingPanel(
      pane,
      header,
      resizer,
      { left: 100, top: 50, width: 400, height: 500 },
      () => {},
    );
    panel.release();

    pointer('pointerdown', header, 10, 10);
    pointer('pointermove', window, 200, 200);

    expect(pane.style.left).toBe('');
  });
});

describe('FloatingPanel in a Valtimo dialog', () => {
  const dialogWithPane = () => {
    document.body.innerHTML = '';
    const layer = document.createElement('section');
    layer.className = 'cds--modal';
    const dialog = document.createElement('div');
    dialog.className = 'cds--modal-container';
    const field = document.createElement('input');
    const pane = document.createElement('div');
    const header = document.createElement('div');
    const resizer = document.createElement('div');
    pane.append(header, resizer);
    dialog.append(field, pane);
    layer.appendChild(dialog);
    document.body.appendChild(layer);
    return { layer, dialog, field, pane, header, resizer };
  };
  const press = (target: EventTarget) =>
    target.dispatchEvent(new MouseEvent('pointerdown', { bubbles: true }));

  it("floats in the dialog's own layer, in front of the dialog", () => {
    const { layer, dialog, pane, header, resizer } = dialogWithPane();

    new FloatingPanel(pane, header, resizer, undefined, () => {});

    expect(pane.parentElement).toBe(layer);
    expect(dialog.contains(pane)).toBe(false);
    expect(pane.style.zIndex).toBe('2');
  });

  it('steps behind the dialog when the dialog is used, and comes forward when it is', () => {
    const { field, pane, header, resizer } = dialogWithPane();
    const panel = new FloatingPanel(pane, header, resizer, undefined, () => {});

    press(field);
    expect(panel.behind).toBe(true);
    // Behind the white dialog box (z-index 1), still above the layer's own backdrop.
    expect(pane.style.zIndex).toBe('0');

    press(pane);
    expect(panel.behind).toBe(false);
    expect(pane.style.zIndex).toBe('2');
  });

  it('stays in front when the dialog would cover it completely, so it can always be reached', () => {
    const { dialog, field, pane, header, resizer } = dialogWithPane();
    dialog.getBoundingClientRect = () =>
      ({ left: 100, top: 50, right: 1100, bottom: 700, width: 1000, height: 650 }) as DOMRect;
    const panel = new FloatingPanel(
      pane,
      header,
      resizer,
      { left: 300, top: 100, width: 400, height: 500 },
      () => {},
    );

    press(field);

    expect(panel.behind).toBe(false);
  });

  it('comes forward when focus moves into it, as a click on the PDF viewer does', () => {
    const { field, pane, header, resizer } = dialogWithPane();
    const panel = new FloatingPanel(pane, header, resizer, undefined, () => {});
    press(field);

    pane.dispatchEvent(new FocusEvent('focusin', { bubbles: true }));

    expect(panel.behind).toBe(false);
  });

  it('goes back into the dialog when docked, in front again', () => {
    const { dialog, field, pane, header, resizer } = dialogWithPane();
    const panel = new FloatingPanel(pane, header, resizer, undefined, () => {});
    press(field);

    panel.release();

    expect(pane.parentElement).toBe(dialog);
    expect(pane.classList.contains('behind')).toBe(false);
    expect(pane.style.zIndex).toBe('');
  });
});

describe('PopOut', () => {
  const memory = () => {
    const items = new Map<string, string>();
    return {
      getItem: (key: string) => items.get(key) ?? null,
      setItem: (key: string, value: string) => void items.set(key, value),
      items,
    } as unknown as Storage & { items: Map<string, string> };
  };

  const preview = () => {
    const host = document.createElement('div');
    const pane = document.createElement('div');
    pane.innerHTML = '<div class="preview-header"></div><div class="preview-resize"></div>';
    host.appendChild(pane);
    document.body.appendChild(host);
    return { host, pane };
  };

  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('floats when asked, remembers it under its own key, and docks again', () => {
    const store = memory();
    const popOut = new PopOut('epistola.documentPreview.previewPanel', undefined, undefined, store);
    const { host, pane } = preview();
    popOut.setElement(pane);

    popOut.toggle();
    expect(pane.parentElement).toBe(document.body);
    expect(JSON.parse(store.items.get('epistola.documentPreview.previewPanel')!).floating).toBe(
      true,
    );
    expect(store.items.has('epistola.composer.previewPanel')).toBe(false);

    popOut.toggle();
    expect(pane.parentElement).toBe(host);
  });

  it('floats a new element at once when it was left floating', () => {
    const store = memory();
    store.setItem('epistola.document.previewPanel', JSON.stringify({ floating: true }));
    const popOut = new PopOut('epistola.document.previewPanel', undefined, undefined, store);
    const { pane } = preview();

    popOut.setElement(pane);

    expect(popOut.floating).toBe(true);
    expect(pane.parentElement).toBe(document.body);
  });

  it('lets go of a floating element when Angular takes it away', () => {
    const store = memory();
    const popOut = new PopOut('k', undefined, undefined, store);
    const { host, pane } = preview();
    popOut.setElement(pane);
    popOut.toggle();
    host.remove();

    popOut.setElement(null);

    expect(document.body.contains(pane)).toBe(false);
  });
});
