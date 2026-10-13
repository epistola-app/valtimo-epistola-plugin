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
  clampGeometry,
  defaultGeometry,
  FloatingPanel,
  MIN_PANEL,
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
