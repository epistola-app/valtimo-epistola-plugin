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

import { needsRichTextHint } from './rich-text-mapping';

describe('needsRichTextHint', () => {
  it('hints when a rich-text field is mapped without $richText', () => {
    expect(needsRichTextHint({ richText: true, value: '$doc.besluit.toelichting' })).toBe(true);
  });

  it('is satisfied by $richText, with or without a format, and with spacing', () => {
    expect(needsRichTextHint({ richText: true, value: '$richText($pv.motivation, "html")' })).toBe(
      false,
    );
    expect(needsRichTextHint({ richText: true, value: '$richText ($pv.motivation)' })).toBe(false);
    expect(
      needsRichTextHint({ richText: true, value: '$doc.items.$richText($, "markdown")' }),
    ).toBe(false);
  });

  it('says nothing while the field is empty', () => {
    expect(needsRichTextHint({ richText: true, value: '' })).toBe(false);
    expect(needsRichTextHint({ richText: true, value: '   ' })).toBe(false);
    expect(needsRichTextHint({ richText: true })).toBe(false);
  });

  it('says nothing for a field that is not rich text', () => {
    expect(needsRichTextHint({ richText: false, value: '$doc.naam' })).toBe(false);
    expect(needsRichTextHint({ value: '$doc.naam' })).toBe(false);
  });
});
