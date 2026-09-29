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
import { of, throwError } from 'rxjs';
import { isRendered, PreviewRenderer, PreviewResult } from './preview-renderer';

/**
 * The object-URL lifecycle, which had no test while it lived inside the component.
 *
 * A preview refreshes on every edit, so a URL that is created and not revoked leaks one per
 * keystroke — invisible in a browser until a long session runs out of them.
 */
describe('PreviewRenderer', () => {
  const sanitizer: any = { bypassSecurityTrustResourceUrl: (url: string) => `safe:${url}` };
  let created: string[];
  let revoked: string[];
  let renderer: PreviewRenderer;

  beforeEach(() => {
    created = [];
    revoked = [];
    let next = 0;
    (globalThis as any).URL.createObjectURL = jest.fn(() => {
      const url = `blob:${++next}`;
      created.push(url);
      return url;
    });
    (globalThis as any).URL.revokeObjectURL = jest.fn((url: string) => revoked.push(url));
    renderer = new PreviewRenderer(sanitizer, () => 'Voorbeeld kon niet worden gegenereerd.');
  });

  const render = (pdf: any): PreviewResult => {
    let result!: PreviewResult;
    renderer.render(pdf).subscribe((value) => (result = value));
    return result;
  };

  it('hands back the rendered letter as something safe to show', () => {
    const result = render(of(new Blob(['%PDF'])));

    expect(isRendered(result)).toBe(true);
    expect((result as any).url).toBe('safe:blob:1');
  });

  it('releases the previous letter before rendering the next', () => {
    render(of(new Blob(['%PDF'])));
    render(of(new Blob(['%PDF'])));

    expect(created).toEqual(['blob:1', 'blob:2']);
    expect(revoked).toEqual(['blob:1']);
  });

  it('releases the last one on teardown, and tolerates being asked twice', () => {
    render(of(new Blob(['%PDF'])));

    renderer.release();
    renderer.release();

    expect(revoked).toEqual(['blob:1']);
  });

  it('releases nothing when nothing was rendered', () => {
    renderer.release();

    expect(revoked).toEqual([]);
  });

  describe('a refused render', () => {
    const refusal = (body: string) => throwError(() => ({ error: new Blob([body]) }));

    it("reads the template's own complaint out of the body", async () => {
      // The request asked for a PDF, so the refusal arrives as a Blob. Read as an error object it
      // says nothing at all.
      let result!: PreviewResult;
      renderer
        .render(refusal(JSON.stringify({ error: "required property 'decisionType' not found" })))
        .subscribe((value) => (result = value));
      await new Promise((resolve) => setTimeout(resolve, 0));

      expect(result).toEqual({ error: "required property 'decisionType' not found" });
    });

    it('prefers the detail when there is one', async () => {
      let result!: PreviewResult;
      renderer
        .render(refusal(JSON.stringify({ error: 'nope', details: 'de reden' })))
        .subscribe((value) => (result = value));
      await new Promise((resolve) => setTimeout(resolve, 0));

      expect(result).toEqual({ error: 'de reden' });
    });

    it('falls back when the body is not the shape we expect', async () => {
      let result!: PreviewResult;
      renderer.render(refusal('<html>502</html>')).subscribe((value) => (result = value));
      await new Promise((resolve) => setTimeout(resolve, 0));

      expect(result).toEqual({ error: 'Voorbeeld kon niet worden gegenereerd.' });
    });

    it('reads a plain error response too', () => {
      const result = render(throwError(() => ({ error: { error: 'geen toegang' } })));

      expect(result).toEqual({ error: 'geen toegang' });
    });

    it('creates no object URL for a letter that never rendered', () => {
      render(throwError(() => ({ error: { error: 'nope' } })));

      expect(created).toEqual([]);
    });
  });
});
