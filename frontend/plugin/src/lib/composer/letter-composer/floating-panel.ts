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

/** Where a floating panel sits and how big it is, in CSS pixels against the viewport. */
export interface PanelGeometry {
  left: number;
  top: number;
  width: number;
  height: number;
}

export interface Viewport {
  width: number;
  height: number;
}

/** The smallest a panel may become, so its header and a readable page always fit. */
export const MIN_PANEL = { width: 320, height: 240 };

/**
 * Where a panel first appears: along the right edge, most of the screen's height, about an A4
 * page's proportion wide. That is beside a Valtimo dialog on any screen wide enough to have room,
 * and over its right half on one that is not, which is still movable.
 */
export function defaultGeometry(viewport: Viewport): PanelGeometry {
  const margin = 16;
  const height = Math.max(MIN_PANEL.height, Math.round(viewport.height * 0.85));
  const width = Math.max(
    MIN_PANEL.width,
    Math.min(Math.round(height * 0.75), viewport.width - 2 * margin),
  );
  return clampGeometry(
    {
      left: viewport.width - width - margin,
      top: Math.round((viewport.height - height) / 2),
      width,
      height,
    },
    viewport,
  );
}

/**
 * The geometry, shrunk and moved until it fits the viewport. A panel remembered from a larger
 * screen, or dragged half out of this one, would otherwise end up with its header — the only thing
 * that moves it back — out of reach.
 */
export function clampGeometry(geometry: PanelGeometry, viewport: Viewport): PanelGeometry {
  const width = Math.min(
    Math.max(geometry.width, MIN_PANEL.width),
    Math.max(MIN_PANEL.width, viewport.width),
  );
  const height = Math.min(
    Math.max(geometry.height, MIN_PANEL.height),
    Math.max(MIN_PANEL.height, viewport.height),
  );
  const left = Math.min(Math.max(geometry.left, 0), Math.max(0, viewport.width - width));
  const top = Math.min(Math.max(geometry.top, 0), Math.max(0, viewport.height - height));
  return { left, top, width, height };
}

/** What is remembered between dialogs: whether the panel floats, and where. Per browser. */
export interface PanelMemory {
  floating: boolean;
  geometry?: PanelGeometry;
}

const STORAGE_KEY = 'epistola.composer.previewPanel';

/**
 * The remembered panel, or a docked one. Browser storage is a convenience: it can be missing,
 * full or forbidden (a private window, a locked-down installation), so every failure reads as
 * "nothing remembered".
 */
export function readPanelMemory(
  storage: Pick<Storage, 'getItem'> | undefined = safeStorage(),
): PanelMemory {
  try {
    const parsed = JSON.parse(storage?.getItem(STORAGE_KEY) ?? 'null');
    if (parsed && typeof parsed.floating === 'boolean') {
      const g = parsed.geometry;
      const valid = g && ['left', 'top', 'width', 'height'].every((k) => Number.isFinite(g[k]));
      return { floating: parsed.floating, geometry: valid ? g : undefined };
    }
  } catch {
    // Unreadable is the same as absent.
  }
  return { floating: false };
}

export function writePanelMemory(
  memory: PanelMemory,
  storage: Pick<Storage, 'setItem'> | undefined = safeStorage(),
): void {
  try {
    storage?.setItem(STORAGE_KEY, JSON.stringify(memory));
  } catch {
    // Not remembered, which costs the employee one drag next time.
  }
}

function safeStorage(): Storage | undefined {
  try {
    return globalThis.localStorage;
  } catch {
    return undefined;
  }
}

/**
 * Detaches an element from wherever Angular put it and floats it over the page: moved to
 * `document.body`, fixed in place, dragged by a handle and resized from a corner.
 *
 * <p>Moved to the body rather than positioned where it is, because a dialog is usually transformed,
 * and a fixed element inside a transformed ancestor is fixed to that ancestor — the panel would
 * stay inside the dialog it is meant to escape. Angular keeps updating the element after the move:
 * bindings follow the node, not its parent.
 *
 * <p>The element still belongs to Angular, so when Angular destroys it the panel has to let go of
 * it ({@link release}); otherwise it would stay on the body, orphaned, after the dialog closed.
 */
export class FloatingPanel {
  private anchor: Comment | null = null;
  private geometry: PanelGeometry;
  private cleanup: Array<() => void> = [];

  constructor(
    private readonly element: HTMLElement,
    private readonly handle: HTMLElement,
    private readonly resizer: HTMLElement,
    geometry: PanelGeometry | undefined,
    private readonly onMoved: (geometry: PanelGeometry) => void,
    private readonly win: Window = window,
  ) {
    this.geometry = clampGeometry(geometry ?? defaultGeometry(this.viewport()), this.viewport());
    this.float();
  }

  /** Back where Angular put it, and no longer listening. */
  release(): void {
    this.cleanup.forEach((undo) => undo());
    this.cleanup = [];
    if (this.anchor?.parentNode) {
      this.anchor.parentNode.insertBefore(this.element, this.anchor);
      this.anchor.remove();
    } else {
      // Angular destroyed the place it came from; there is nowhere to go back to.
      this.element.remove();
    }
    this.anchor = null;
    ['position', 'left', 'top', 'width', 'height', 'z-index'].forEach((p) =>
      this.element.style.removeProperty(p),
    );
  }

  private float(): void {
    const doc = this.element.ownerDocument;
    this.anchor = doc.createComment('epistola-preview-docked');
    this.element.parentNode?.insertBefore(this.anchor, this.element);
    doc.body.appendChild(this.element);
    this.apply();
    this.listen(this.handle, 'pointerdown', (event) => this.drag(event as PointerEvent, 'move'));
    this.listen(this.resizer, 'pointerdown', (event) => this.drag(event as PointerEvent, 'resize'));
    this.listen(this.win, 'resize', () => {
      this.geometry = clampGeometry(this.geometry, this.viewport());
      this.apply();
    });
  }

  private drag(start: PointerEvent, mode: 'move' | 'resize'): void {
    // A press on a button in the header is the button's, not a drag.
    if (mode === 'move' && (start.target as HTMLElement)?.closest?.('button, input, label, a')) {
      return;
    }
    start.preventDefault();
    const from = { ...this.geometry };
    // The PDF viewer swallows pointer events, so a drag that crosses it would stop dead.
    this.element.classList.add('dragging');
    const move = (event: Event) => {
      const e = event as PointerEvent;
      const dx = e.clientX - start.clientX;
      const dy = e.clientY - start.clientY;
      this.geometry = clampGeometry(
        mode === 'move'
          ? { ...from, left: from.left + dx, top: from.top + dy }
          : { ...from, width: from.width + dx, height: from.height + dy },
        this.viewport(),
      );
      this.apply();
    };
    const end = () => {
      this.element.classList.remove('dragging');
      this.win.removeEventListener('pointermove', move);
      this.win.removeEventListener('pointerup', end);
      this.onMoved(this.geometry);
    };
    this.win.addEventListener('pointermove', move);
    this.win.addEventListener('pointerup', end);
  }

  private apply(): void {
    const s = this.element.style;
    s.position = 'fixed';
    s.left = `${this.geometry.left}px`;
    s.top = `${this.geometry.top}px`;
    s.width = `${this.geometry.width}px`;
    s.height = `${this.geometry.height}px`;
    // Above Carbon's modal (9000), which is what the composer usually sits in.
    s.zIndex = '9500';
  }

  private viewport(): Viewport {
    return { width: this.win.innerWidth, height: this.win.innerHeight };
  }

  private listen(target: EventTarget, type: string, listener: (event: Event) => void): void {
    target.addEventListener(type, listener);
    this.cleanup.push(() => target.removeEventListener(type, listener));
  }
}
