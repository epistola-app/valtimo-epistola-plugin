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

/**
 * What this file relies on in Carbon's modal (carbon-components-angular `cds-modal`, styled by
 * `@carbon/styles`), which is how Valtimo opens a task. Carbon's, not ours, so it is checked against
 * the installed version by `carbon-modal.contract.spec.ts`: an upgrade that changes it fails the
 * build instead of quietly changing how a floating preview stacks.
 */
export const CARBON_MODAL = {
  /** The full-screen layer: fixed, its own background the backdrop, no transform. */
  layerClass: 'cds--modal',
  /** The white dialog box, a child of the layer. */
  dialogClass: 'cds--modal-container',
  /** The box's z-index within the layer (an inline style in Carbon's template). */
  dialogZIndex: 1,
  /** The layer's z-index on the page (`z('modal')`). */
  layerZIndex: 9000,
} as const;

/** The z-index a floating panel takes: in a dialog's layer around its box, or on the page above any dialog. */
export const PANEL_Z_INDEX = {
  behindDialog: CARBON_MODAL.dialogZIndex - 1,
  inFrontOfDialog: CARBON_MODAL.dialogZIndex + 1,
  onPage: CARBON_MODAL.layerZIndex + 500,
} as const;

/** How much of a panel must show beside a dialog for it to go behind it, in CSS pixels. */
const VISIBLE_STRIP = 40;

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

/**
 * What is remembered between dialogs, per browser: whether the preview floats and where, and how
 * it was sized while docked.
 */
export interface PanelMemory {
  floating: boolean;
  geometry?: PanelGeometry;
  /** The docked preview's share of the composer's width, 0.25–0.75. */
  dockedWidth?: number;
  /** The docked preview's height in pixels. */
  dockedHeight?: number;
}

/** The narrowest and widest share of the composer the docked preview may take. */
export const DOCKED_WIDTH = { min: 0.25, max: 0.75 };

export function clampDockedWidth(share: number): number {
  return Math.min(Math.max(share, DOCKED_WIDTH.min), DOCKED_WIDTH.max);
}

/** At least a usable page, and never taller than the screen it is on. */
export function clampDockedHeight(height: number, viewportHeight: number): number {
  return Math.round(
    Math.min(Math.max(height, MIN_PANEL.height), Math.max(MIN_PANEL.height, viewportHeight)),
  );
}

/** Where each kind of preview remembers its panel; the composer's key predates the others. */
export const PANEL_MEMORY_KEYS = {
  composer: 'epistola.composer.previewPanel',
  documentPreview: 'epistola.documentPreview.previewPanel',
  document: 'epistola.document.previewPanel',
} as const;

/**
 * The remembered panel, or a docked one. Browser storage is a convenience: it can be missing,
 * full or forbidden (a private window, a locked-down installation), so every failure reads as
 * "nothing remembered".
 */
export function readPanelMemory(
  storage: Pick<Storage, 'getItem'> | undefined = safeStorage(),
  key: string = PANEL_MEMORY_KEYS.composer,
): PanelMemory {
  try {
    const parsed = JSON.parse(storage?.getItem(key) ?? 'null');
    if (parsed && typeof parsed.floating === 'boolean') {
      const g = parsed.geometry;
      const valid = g && ['left', 'top', 'width', 'height'].every((k) => Number.isFinite(g[k]));
      const memory: PanelMemory = { floating: parsed.floating, geometry: valid ? g : undefined };
      if (Number.isFinite(parsed.dockedWidth)) {
        memory.dockedWidth = clampDockedWidth(parsed.dockedWidth);
      }
      if (Number.isFinite(parsed.dockedHeight)) {
        memory.dockedHeight = parsed.dockedHeight;
      }
      return memory;
    }
  } catch {
    // Unreadable is the same as absent.
  }
  return { floating: false };
}

export function writePanelMemory(
  memory: PanelMemory,
  storage: Pick<Storage, 'setItem'> | undefined = safeStorage(),
  key: string = PANEL_MEMORY_KEYS.composer,
): void {
  try {
    storage?.setItem(key, JSON.stringify(memory));
  } catch {
    // Not remembered, which costs the employee one drag next time.
  }
}

/** The browser's storage, or nothing where it is forbidden. */
export function safeStorage(): Storage | undefined {
  try {
    return globalThis.localStorage;
  } catch {
    return undefined;
  }
}

/**
 * Follow one pointer drag: `onMove` with the distance from where it started, `onEnd` once it is
 * let go. Listens on the window, so a drag that leaves the element it started on keeps going.
 */
export function trackDrag(
  start: PointerEvent,
  win: Window,
  onMove: (dx: number, dy: number) => void,
  onEnd: () => void,
): void {
  start.preventDefault();
  const move = (event: Event) => {
    const e = event as PointerEvent;
    onMove(e.clientX - start.clientX, e.clientY - start.clientY);
  };
  const end = () => {
    win.removeEventListener('pointermove', move);
    win.removeEventListener('pointerup', end);
    onEnd();
  };
  win.addEventListener('pointermove', move);
  win.addEventListener('pointerup', end);
}

/**
 * Detaches an element from wherever Angular put it and floats it over the page, fixed in place,
 * dragged by a handle and resized from a corner.
 *
 * <p>Where it goes depends on where it was. Inside a Carbon dialog — which is how Valtimo opens a
 * task — it moves into the dialog's own layer (`.cds--modal`, the fixed full-screen element whose
 * background is the grey backdrop), beside the white dialog box. Stacked there it can be in front
 * of the dialog or behind it while staying above the backdrop: a click in the dialog sends it back
 * so it never covers what is being typed in, and a click on what is still visible of it brings it
 * forward, like windows on a desktop. Anywhere else it moves to `document.body`, above everything.
 * Either way it leaves the place Angular put it, because a fixed element inside a transformed
 * ancestor is fixed to that ancestor. Angular keeps updating the element after the move: bindings
 * follow the node, not its parent.
 *
 * <p>The element still belongs to Angular, so when Angular destroys it the panel has to let go of
 * it ({@link release}); otherwise it would stay on the body, orphaned, after the dialog closed.
 */
export class FloatingPanel {
  /** Carbon's modal layer the panel shares with a dialog, or null when it floats on the body. */
  private layer: HTMLElement | null = null;
  /** The white dialog box in that layer. */
  private dialog: HTMLElement | null = null;
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
    this.layer = null;
    this.dialog = null;
    this.element.classList.remove('behind');
    ['position', 'left', 'top', 'width', 'height', 'z-index'].forEach((p) =>
      this.element.style.removeProperty(p),
    );
  }

  /** Whether the panel is currently behind the dialog it shares a layer with. */
  get behind(): boolean {
    return this.element.classList.contains('behind');
  }

  /**
   * In front of the dialog, or behind it. Only meaningful inside a dialog's layer, and only behind
   * while enough of it still shows beside the dialog to click it forward again: a panel the dialog
   * would cover completely stays in front, or it could not be reached until the dialog closed.
   */
  private stack(behind: boolean): void {
    if (!this.layer || behind === this.behind) {
      return;
    }
    if (behind && !this.showsBeside(this.dialog)) {
      return;
    }
    this.element.classList.toggle('behind', behind);
    this.apply();
  }

  private float(): void {
    const doc = this.element.ownerDocument;
    this.anchor = doc.createComment('epistola-preview-docked');
    this.element.parentNode?.insertBefore(this.anchor, this.element);
    this.layer = this.element.closest<HTMLElement>(`.${CARBON_MODAL.layerClass}`);
    (this.layer ?? doc.body).appendChild(this.element);
    this.apply();
    const dialog = this.layer?.querySelector<HTMLElement>(`.${CARBON_MODAL.dialogClass}`);
    this.dialog = dialog ?? null;
    if (dialog) {
      // Capture, so it is seen before anything in the dialog stops it.
      this.listen(dialog, 'pointerdown', () => this.stack(true), true);
      this.listen(this.element, 'pointerdown', () => this.stack(false), true);
      // A click on the PDF itself goes to the browser's viewer, not to the page, but it does move
      // focus to the viewer's element: that is how a click on the letter brings it forward.
      this.listen(this.element, 'focusin', () => this.stack(false));
      this.listen(dialog, 'focusin', () => this.stack(true));
    }
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
    const from = { ...this.geometry };
    // The PDF viewer swallows pointer events, so a drag that crosses it would stop dead.
    this.element.classList.add('dragging');
    trackDrag(
      start,
      this.win,
      (dx, dy) => {
        this.geometry = clampGeometry(
          mode === 'move'
            ? { ...from, left: from.left + dx, top: from.top + dy }
            : { ...from, width: from.width + dx, height: from.height + dy },
          this.viewport(),
        );
        this.apply();
      },
      () => {
        this.element.classList.remove('dragging');
        this.onMoved(this.geometry);
      },
    );
  }

  private apply(): void {
    const s = this.element.style;
    s.position = 'fixed';
    s.left = `${this.geometry.left}px`;
    s.top = `${this.geometry.top}px`;
    s.width = `${this.geometry.width}px`;
    s.height = `${this.geometry.height}px`;
    // In a dialog's layer the white dialog box is z-index 1, and the layer's own background is the
    // backdrop: 2 is in front of the box, 0 behind it but still above the backdrop. On the body,
    // above Carbon's modals (9000).
    s.zIndex = String(
      this.layer
        ? this.behind
          ? PANEL_Z_INDEX.behindDialog
          : PANEL_Z_INDEX.inFrontOfDialog
        : PANEL_Z_INDEX.onPage,
    );
  }

  /** Whether at least a clickable strip of the panel lies outside the dialog box. */
  private showsBeside(dialog: HTMLElement | null): boolean {
    if (!dialog) {
      return false;
    }
    const box = dialog.getBoundingClientRect();
    const g = this.geometry;
    return (
      box.left - g.left >= VISIBLE_STRIP ||
      g.left + g.width - box.right >= VISIBLE_STRIP ||
      box.top - g.top >= VISIBLE_STRIP ||
      g.top + g.height - box.bottom >= VISIBLE_STRIP
    );
  }

  private viewport(): Viewport {
    return { width: this.win.innerWidth, height: this.win.innerHeight };
  }

  private listen(
    target: EventTarget,
    type: string,
    listener: (event: Event) => void,
    capture = false,
  ): void {
    target.addEventListener(type, listener, capture);
    this.cleanup.push(() => target.removeEventListener(type, listener, capture));
  }
}

/**
 * Pop a preview out of its form and back, for a component that shows one.
 *
 * <p>Holds what the component would otherwise repeat: whether it floats (remembered per browser,
 * per kind of preview), floating the element when Angular hands it over, and letting go of it when
 * Angular takes it away — a preview usually lives inside an `*ngIf`, and an element left floating on
 * the page after its component is gone would be an orphan nobody can close.
 *
 * <p>The element needs a header to drag (`handleSelector`) and a corner to resize from
 * (`resizerSelector`) inside it.
 */
export class PopOut {
  floating: boolean;
  private panel: FloatingPanel | null = null;
  private element: HTMLElement | null = null;

  constructor(
    private readonly memoryKey: string,
    private readonly handleSelector = '.preview-header',
    private readonly resizerSelector = '.preview-resize',
    private readonly storage: Storage | undefined = safeStorage(),
  ) {
    this.floating = readPanelMemory(this.storage, this.memoryKey).floating;
  }

  /** The preview's element, whenever Angular (re)creates or removes it. */
  setElement(element: HTMLElement | null): void {
    if (element === this.element) {
      return;
    }
    this.release();
    this.element = element;
    if (element && this.floating) {
      this.start(element);
    }
  }

  /** Float it, or put it back. */
  toggle(): void {
    this.floating = !this.floating;
    writePanelMemory(
      { ...readPanelMemory(this.storage, this.memoryKey), floating: this.floating },
      this.storage,
      this.memoryKey,
    );
    if (this.floating && this.element) {
      this.start(this.element);
    } else {
      this.release();
    }
  }

  /** Back where Angular put it. Call from `ngOnDestroy`. */
  release(): void {
    this.panel?.release();
    this.panel = null;
  }

  private start(element: HTMLElement): void {
    // A component's element can be handed over before Angular has put it in the page; floated then,
    // it would not find the dialog it belongs to. Try again once it is there.
    if (!element.isConnected) {
      globalThis.requestAnimationFrame?.(() => {
        if (element === this.element && this.floating && !this.panel) {
          this.start(element);
        }
      });
      return;
    }
    const handle = element.querySelector<HTMLElement>(this.handleSelector);
    const resizer = element.querySelector<HTMLElement>(this.resizerSelector);
    if (!handle || !resizer) {
      return;
    }
    const memory = readPanelMemory(this.storage, this.memoryKey);
    this.panel = new FloatingPanel(element, handle, resizer, memory.geometry, (geometry) =>
      writePanelMemory(
        { ...readPanelMemory(this.storage, this.memoryKey), floating: true, geometry },
        this.storage,
        this.memoryKey,
      ),
    );
  }
}
