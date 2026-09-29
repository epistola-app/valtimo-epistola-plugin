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
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { Observable } from 'rxjs';

/** A rendered letter, or why it could not be rendered. */
export type PreviewResult = { url: SafeResourceUrl } | { error: string };

export function isRendered(result: PreviewResult): result is { url: SafeResourceUrl } {
  return 'url' in result;
}

/**
 * Turns a rendered PDF into something an `<object>` can show, and cleans up after itself.
 *
 * <p>Two things here are easy to get wrong and invisible when you do. An object URL lives until it
 * is revoked, so a preview that refreshes on every edit leaks one per keystroke unless the
 * previous one is released first — which is why creating and revoking live together rather than
 * with the component's state. And a refused render arrives as a `Blob`, because the request asked
 * for a PDF: read as an error object it says nothing, so the template's own complaint has to be
 * read back out of the body or the employee is told only that something failed.
 */
export class PreviewRenderer {
  private currentUrl: string | null = null;

  constructor(
    private readonly sanitizer: DomSanitizer,
    private readonly fallbackMessage: () => string,
  ) {}

  /**
   * Render one preview, releasing whatever was on screen before it.
   *
   * <p>The previous URL is released immediately rather than when the next one arrives: a preview
   * being replaced is already stale, and holding it until the request returns means holding two.
   */
  render(pdf: Observable<Blob>): Observable<PreviewResult> {
    this.release();
    return new Observable<PreviewResult>((subscriber) => {
      const subscription = pdf.subscribe({
        next: (blob) => {
          this.currentUrl = URL.createObjectURL(blob);
          subscriber.next({ url: this.sanitizer.bypassSecurityTrustResourceUrl(this.currentUrl) });
          subscriber.complete();
        },
        error: (failure) => {
          this.readError(failure, (error) => {
            subscriber.next({ error });
            subscriber.complete();
          });
        },
      });
      return () => subscription.unsubscribe();
    });
  }

  /** Let go of the rendered letter, if there is one. Safe to call repeatedly. */
  release(): void {
    if (this.currentUrl) {
      URL.revokeObjectURL(this.currentUrl);
      this.currentUrl = null;
    }
  }

  /**
   * A refused render arrives as a Blob body, because the request asked for a PDF. Read it as text
   * so the template's own complaint reaches the employee instead of a generic failure.
   */
  private readError(failure: any, done: (message: string) => void): void {
    const fallback = this.fallbackMessage();
    if (failure?.error instanceof Blob) {
      failure.error
        .text()
        .then((text: string) => {
          try {
            const body = JSON.parse(text);
            done(body.details || body.error || fallback);
          } catch {
            done(fallback);
          }
        })
        .catch(() => done(fallback));
      return;
    }
    done(failure?.error?.error || fallback);
  }
}
