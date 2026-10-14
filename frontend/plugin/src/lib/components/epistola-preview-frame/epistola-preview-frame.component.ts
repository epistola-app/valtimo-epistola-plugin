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
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  ElementRef,
  Input,
  OnChanges,
  OnDestroy,
  OnInit,
  SimpleChanges,
  ViewChild,
  ViewEncapsulation,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { PluginTranslatePipeModule, PluginTranslationService } from '@valtimo/plugin';
import { DockedSize } from '../../utils/docked-size';
import { PopOut } from '../../utils/floating-panel';
import { windowViewUrl } from '../../utils/pdf-view';
import { PreviewWindow } from '../../utils/preview-window';

/**
 * The box every Epistola preview shows its letter in: a heading bar in Valtimo's (Carbon) style,
 * the letter under it, and the means to pop it out of its form and back.
 *
 * <p>One frame for the composer's preview, the document preview and the document component, so the
 * three look and behave alike and the floating panel's wiring lives here once: handing the element
 * to {@link PopOut}, and letting go of it when Angular destroys the frame, which is what keeps a
 * panel from staying on the page after its form has gone.
 *
 * <p>What goes in it is the component's: controls for the heading bar (`previewControls`), a status
 * beside the title (`previewStatus`), and the letter itself as the body. Its styles are not
 * encapsulated so that projected content can use them; every class is prefixed
 * `epistola-preview-frame`. The PDF itself takes `epistola-preview-frame__pdf`, which fills the body
 * docked and floating alike.
 */
@Component({
  standalone: true,
  imports: [CommonModule, PluginTranslatePipeModule],
  selector: 'epistola-preview-frame',
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  template: `
    <div
      #frame
      role="region"
      [attr.aria-label]="title"
      class="epistola-preview-frame"
      [class.floating]="floating"
      [class.windowed]="windowed"
      [attr.data-testid]="testId"
    >
      <div class="epistola-preview-frame__header" [attr.data-testid]="testId + '-header'">
        <div class="epistola-preview-frame__title" role="heading" aria-level="2">
          {{ title }}
        </div>
        <ng-content select="[previewStatus]"></ng-content>
        <div class="epistola-preview-frame__controls">
          <ng-content select="[previewControls]"></ng-content>
          <button
            type="button"
            class="cds--btn cds--btn--ghost cds--btn--sm epistola-preview-frame__icon-button"
            [attr.title]="
              (windowed ? 'previewFrameWindowBack' : 'previewFrameWindow')
                | pluginTranslate: pluginId
                | async
            "
            [attr.aria-label]="
              (windowed ? 'previewFrameWindowBack' : 'previewFrameWindow')
                | pluginTranslate: pluginId
                | async
            "
            (click)="toggleWindow()"
            [attr.data-testid]="testId + '-window'"
          >
            <i
              class="mdi"
              [class.mdi-open-in-new]="!windowed"
              [class.mdi-arrow-collapse]="windowed"
              aria-hidden="true"
            ></i>
          </button>
          <button
            *ngIf="!windowed"
            type="button"
            class="cds--btn cds--btn--ghost cds--btn--sm epistola-preview-frame__icon-button"
            [attr.title]="
              (floating ? 'previewFrameDock' : 'previewFramePopOut')
                | pluginTranslate: pluginId
                | async
            "
            [attr.aria-label]="
              (floating ? 'previewFrameDock' : 'previewFramePopOut')
                | pluginTranslate: pluginId
                | async
            "
            (click)="togglePopOut()"
            [attr.data-testid]="testId + '-popout'"
          >
            <i
              class="mdi"
              [class.mdi-picture-in-picture-bottom-right]="!floating"
              [class.mdi-dock-right]="floating"
              aria-hidden="true"
            ></i>
          </button>
        </div>
      </div>
      <div
        class="epistola-preview-frame__body"
        [style.height.px]="!floating && !windowed && docked?.height ? docked!.height : null"
      >
        <!-- In its own window, only the PDF goes there: what the employee is told stays here. -->
        <p
          *ngIf="windowed"
          class="epistola-preview-frame__message"
          [attr.data-testid]="testId + '-windowed'"
        >
          {{ 'previewFrameWindowOpen' | pluginTranslate: pluginId | async }}
        </p>
        <p
          *ngIf="windowBlocked"
          class="epistola-preview-frame__message epistola-preview-frame__message--error"
          [attr.data-testid]="testId + '-window-blocked'"
        >
          {{ 'previewFrameWindowBlocked' | pluginTranslate: pluginId | async }}
        </p>
        <ng-content></ng-content>
      </div>
      <div
        *ngIf="resizable"
        class="epistola-preview-frame__grip"
        role="separator"
        aria-orientation="horizontal"
        [attr.aria-label]="'previewFrameResize' | pluginTranslate: pluginId | async"
        (pointerdown)="resizeHeight($event)"
        [attr.data-testid]="testId + '-grip'"
      ></div>
      <div class="epistola-preview-frame__resize" aria-hidden="true"></div>
    </div>
  `,
  styles: [
    `
      /*
       * The box, in Valtimo's (Carbon) colours: a bordered layer with a heading bar. Plain divs with
       * ARIA roles rather than section/header/h4, which a host's global styles reach.
       */
      .epistola-preview-frame {
        position: relative;
        display: flex;
        flex-direction: column;
        background: var(--cds-layer-01, #f4f4f4);
        border: 1px solid var(--cds-border-subtle-01, #c6c6c6);
      }
      .epistola-preview-frame__header {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 0 0.75rem;
        min-height: 2.5rem;
        padding: 0 0.25rem 0 1rem;
        background: var(--cds-layer-accent-01, #e0e0e0);
        border-bottom: 1px solid var(--cds-border-subtle-01, #c6c6c6);
      }
      /* Not an h-element: a host's global heading styles (Valtimo's dialogs have them) would win. */
      .epistola-preview-frame .epistola-preview-frame__title {
        margin: 0;
        font-size: 0.875rem;
        font-weight: 600;
        line-height: 1.28572;
        letter-spacing: 0.16px;
        color: var(--cds-text-primary, #161616);
      }
      .epistola-preview-frame__status {
        font-size: 0.75rem;
        color: var(--cds-text-secondary, #525252);
      }
      .epistola-preview-frame__status--warning {
        color: var(--cds-support-warning-inverse, #8e6a00);
      }
      .epistola-preview-frame__controls {
        display: flex;
        align-items: center;
        gap: 0.25rem;
        margin-left: auto;
      }
      .epistola-preview-frame__checkbox {
        margin: 0 0.5rem 0 0;
      }
      .epistola-preview-frame__icon-button {
        padding: 0 0.5rem;
        min-height: 2rem;
      }
      .epistola-preview-frame__icon-button .mdi {
        font-size: 1.125rem;
        line-height: 1;
      }
      /* Fits a laptop's task dialog with its header and grip; the grip makes it taller. */
      .epistola-preview-frame__body {
        height: clamp(18rem, calc(100vh - 20rem), 60rem);
        padding: 0.5rem;
        overflow: auto;
      }
      .epistola-preview-frame__pdf {
        display: block;
        width: 100%;
        height: 100%;
        border: 0;
      }
      .epistola-preview-frame__message {
        padding: 1rem;
        color: var(--cds-text-secondary, #525252);
      }
      .epistola-preview-frame__message--error {
        color: var(--cds-text-error, #da1e28);
      }
      /* Drag down to make the docked letter taller. */
      .epistola-preview-frame__grip {
        height: 0.5rem;
        cursor: ns-resize;
        touch-action: none;
        border-top: 1px solid var(--cds-border-subtle-01, #c6c6c6);
        background: var(--cds-layer-accent-01, #e0e0e0);
      }
      .epistola-preview-frame__resize {
        display: none;
      }
      /* Floating: moved out of the form by PopOut, which sets its place and size. */
      .epistola-preview-frame.floating {
        box-shadow: 0 2px 6px rgba(0, 0, 0, 0.3);
      }
      .epistola-preview-frame.floating .epistola-preview-frame__header {
        cursor: move;
        user-select: none;
      }
      .epistola-preview-frame.floating .epistola-preview-frame__body {
        flex: 1 1 auto;
        min-height: 0;
        height: auto;
      }
      .epistola-preview-frame.floating .epistola-preview-frame__grip,
      .epistola-preview-frame.windowed .epistola-preview-frame__grip {
        display: none;
      }
      /* In its own window: the letter is there, so the body here only holds what is said. */
      .epistola-preview-frame.windowed .epistola-preview-frame__body {
        height: auto;
      }
      .epistola-preview-frame.windowed .epistola-preview-frame__pdf {
        display: none;
      }
      .epistola-preview-frame.floating .epistola-preview-frame__resize {
        display: block;
        position: absolute;
        right: 0;
        bottom: 0;
        width: 1rem;
        height: 1rem;
        cursor: nwse-resize;
        touch-action: none;
        background: linear-gradient(
          135deg,
          transparent 50%,
          var(--cds-border-strong-01, #8d8d8d) 50%
        );
      }
      /* The PDF viewer would take the pointer mid-drag and stop it dead. */
      .dragging .epistola-preview-frame__pdf {
        pointer-events: none;
      }
    `,
  ],
})
export class EpistolaPreviewFrameComponent implements OnInit, OnChanges, OnDestroy {
  /** The heading, e.g. "Voorbeeld" or the component's label. */
  @Input() title = '';
  /** Where this kind of preview remembers its panel; one of `PANEL_MEMORY_KEYS`. */
  @Input({ required: true }) memoryKey!: string;
  /** The frame's `data-testid`; its header, pop-out button and grip take it as a prefix. */
  @Input() testId = 'epistola-preview-frame';
  /** Whether the docked frame has a grip below it to make it taller or shorter. */
  @Input() resizable = false;
  /**
   * The rendered letter's object URL, as a plain string, for showing it in a window of its own;
   * null while there is none. The component's own `<object>` keeps showing it in the form.
   */
  @Input() windowUrl: string | null = null;

  /** True after the browser refused to open the window, until it opens. */
  windowBlocked = false;
  private readonly previewWindow = new PreviewWindow(() => {
    // Closed by the employee: the letter is back in the form.
    this.cdr.markForCheck();
  });

  readonly pluginId = 'epistola';
  docked: DockedSize | null = null;
  private popOut: PopOut | null = null;
  private element: HTMLElement | null = null;

  constructor(
    private readonly cdr: ChangeDetectorRef,
    private readonly translations: PluginTranslationService,
  ) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['windowUrl'] || changes['title']) {
      this.previewWindow.update(
        this.windowUrl ? windowViewUrl(this.windowUrl) : null,
        this.windowTitle,
      );
    }
  }

  /** Whether the letter is shown in a window of its own. */
  get windowed(): boolean {
    return this.previewWindow.open;
  }

  /**
   * Show the letter in a window of its own, or bring it back. One window for every preview
   * ({@link PREVIEW_WINDOW_NAME}), which closes with the form and with the page.
   */
  toggleWindow(): void {
    if (this.windowed) {
      this.previewWindow.close();
    } else {
      if (this.floating) {
        this.togglePopOut();
      }
      this.windowBlocked = !this.previewWindow.show(
        this.windowTitle,
        this.windowUrl ? windowViewUrl(this.windowUrl) : null,
        this.translations.instant('previewFrameWindowWaiting', this.pluginId),
      );
    }
    this.cdr.markForCheck();
  }

  private get windowTitle(): string {
    return this.title ? `${this.title} — Epistola` : 'Epistola';
  }

  ngOnInit(): void {
    this.popOut = new PopOut(
      this.memoryKey,
      '.epistola-preview-frame__header',
      '.epistola-preview-frame__resize',
    );
    this.docked = new DockedSize(this.memoryKey);
    this.popOut.setElement(this.element);
  }

  /** The frame's element, once rendered; floated at once if it was left floating. */
  @ViewChild('frame', { static: true })
  set frame(ref: ElementRef<HTMLElement> | undefined) {
    this.element = ref?.nativeElement ?? null;
    this.popOut?.setElement(this.element);
  }

  /** Whether the frame floats over the page instead of sitting in its form. */
  get floating(): boolean {
    return this.popOut?.floating ?? false;
  }

  /** Float the frame over the page, or put it back. Remembered per browser. */
  togglePopOut(): void {
    this.popOut?.toggle();
    this.cdr.markForCheck();
  }

  resizeHeight(start: PointerEvent): void {
    const body = this.element?.querySelector<HTMLElement>('.epistola-preview-frame__body');
    if (!this.element || !body || !this.docked || this.floating) {
      return;
    }
    this.docked.dragHeight(start, this.element, body, () => this.cdr.markForCheck());
  }

  ngOnDestroy(): void {
    this.previewWindow.close();
    this.popOut?.release();
  }
}
