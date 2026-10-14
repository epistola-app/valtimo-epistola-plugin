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
  readPanelMemory,
  safeStorage,
  trackDrag,
  writePanelMemory,
} from './floating-panel';

/**
 * The size of a docked preview — its share of the width beside a form and its height — as the
 * employee dragged it, remembered per browser with the rest of that preview's panel memory.
 *
 * <p>Null means "as laid out": the component's own CSS decides until the employee drags. During a
 * drag the preview gets the `dragging` class, since a PDF viewer under the pointer would take it
 * and stop the drag dead.
 */
export class DockedSize {
  width: number | null;
  height: number | null;

  constructor(
    private readonly memoryKey: string,
    private readonly storage: Storage | undefined = safeStorage(),
    /** Looked up when a drag starts, not now: a component can be created where there is none. */
    private readonly win: () => Window = () => globalThis.window,
  ) {
    const memory = readPanelMemory(this.storage, this.memoryKey);
    this.width = memory.dockedWidth ?? null;
    this.height = memory.dockedHeight ?? null;
  }

  /**
   * Drag the divider to the left of `preview` across `container`: dragging left gives the preview
   * more of the width.
   */
  dragWidth(
    start: PointerEvent,
    preview: HTMLElement,
    container: HTMLElement,
    changed: () => void,
  ): void {
    const containerWidth = container.getBoundingClientRect().width;
    const from = preview.getBoundingClientRect().width / containerWidth;
    this.drag(start, preview, changed, 'dockedWidth', (dx) => {
      this.width = clampDockedWidth(from - dx / containerWidth);
      return this.width;
    });
  }

  /** Drag the grip below `content`, which is the part of the preview that grows. */
  dragHeight(
    start: PointerEvent,
    preview: HTMLElement,
    content: HTMLElement,
    changed: () => void,
  ): void {
    const from = content.getBoundingClientRect().height;
    this.drag(start, preview, changed, 'dockedHeight', (_dx, dy) => {
      this.height = clampDockedHeight(from + dy, this.win().innerHeight);
      return this.height;
    });
  }

  private drag(
    start: PointerEvent,
    preview: HTMLElement,
    changed: () => void,
    dimension: 'dockedWidth' | 'dockedHeight',
    resize: (dx: number, dy: number) => number,
  ): void {
    preview.classList.add('dragging');
    let size: number | null = null;
    trackDrag(
      start,
      this.win(),
      (dx, dy) => {
        size = resize(dx, dy);
        changed();
      },
      () => {
        preview.classList.remove('dragging');
        if (size === null) {
          return;
        }
        // Only the dimension dragged: a width and a height may be kept by different owners of the
        // same preview (the composer its divider, the frame its grip), each reading the memory once.
        writePanelMemory(
          { ...readPanelMemory(this.storage, this.memoryKey), [dimension]: size },
          this.storage,
          this.memoryKey,
        );
      },
    );
  }
}
