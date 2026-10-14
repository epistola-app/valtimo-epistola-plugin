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

import { DockedSize } from './docked-size';

describe('DockedSize', () => {
  const KEY = 'epistola.composer.previewPanel';
  const memory = () => {
    const items = new Map<string, string>();
    return {
      getItem: (key: string) => items.get(key) ?? null,
      setItem: (key: string, value: string) => void items.set(key, value),
      items,
    } as unknown as Storage & { items: Map<string, string> };
  };
  const box = (width: number, height: number) => {
    const el = document.createElement('div');
    el.getBoundingClientRect = () => ({ width, height }) as DOMRect;
    return el;
  };
  const drag = (dx: number, dy: number) => {
    window.dispatchEvent(new MouseEvent('pointermove', { clientX: 100 + dx, clientY: 100 + dy }));
    window.dispatchEvent(new MouseEvent('pointerup'));
  };
  const start = () => new MouseEvent('pointerdown', { clientX: 100, clientY: 100 }) as PointerEvent;

  beforeEach(() => Object.assign(window, { innerHeight: 720 }));

  it('starts as laid out, and with what was remembered', () => {
    const store = memory();
    expect(new DockedSize(KEY, store).width).toBeNull();

    store.setItem(KEY, JSON.stringify({ floating: false, dockedWidth: 0.6, dockedHeight: 500 }));
    const sized = new DockedSize(KEY, store);

    expect(sized.width).toBe(0.6);
    expect(sized.height).toBe(500);
  });

  it('gives the preview more of the width when its divider is dragged left, and remembers it', () => {
    const store = memory();
    const sized = new DockedSize(KEY, store);
    const preview = box(400, 0);
    const changed = jest.fn();

    sized.dragWidth(start(), preview, box(1000, 0), changed);
    expect(preview.classList.contains('dragging')).toBe(true);
    drag(-100, 0);

    expect(sized.width).toBe(0.5);
    expect(changed).toHaveBeenCalled();
    expect(preview.classList.contains('dragging')).toBe(false);
    expect(JSON.parse(store.items.get(KEY)!).dockedWidth).toBe(0.5);
  });

  it('writes only the size it changed, so a width and a height kept apart do not overwrite each other', () => {
    const store = memory();
    const width = new DockedSize(KEY, store);
    const height = new DockedSize(KEY, store);

    width.dragWidth(start(), box(400, 0), box(1000, 0), () => {});
    drag(-100, 0);
    height.dragHeight(start(), box(0, 0), box(0, 400), () => {});
    drag(0, 100);

    expect(JSON.parse(store.items.get(KEY)!)).toMatchObject({
      dockedWidth: 0.5,
      dockedHeight: 500,
    });
  });

  it('remembers nothing for a press that never moved', () => {
    const store = memory();
    const sized = new DockedSize(KEY, store);

    sized.dragHeight(start(), box(0, 0), box(0, 400), () => {});
    window.dispatchEvent(new MouseEvent('pointerup'));

    expect(store.items.has(KEY)).toBe(false);
  });
});
