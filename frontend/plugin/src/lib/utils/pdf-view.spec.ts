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

import { pdfViewUrl } from './pdf-view';

describe('pdfViewUrl', () => {
  it('asks the viewer for the letter alone: no toolbar, no side panel, fitted to the width', () => {
    expect(pdfViewUrl('blob:http://localhost/abc')).toBe(
      'blob:http://localhost/abc#toolbar=0&navpanes=0&view=FitH',
    );
  });

  it('replaces a fragment the URL already had rather than adding a second', () => {
    expect(pdfViewUrl('blob:http://localhost/abc#page=2')).toBe(
      'blob:http://localhost/abc#toolbar=0&navpanes=0&view=FitH',
    );
  });
});
