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

import { RICH_TEXT_PROPERTY, RICH_TEXT_TAG, tagRichText } from './rich-text-values';

const editor = (key: string, kind = 'BLOCK') => ({
  type: 'textarea',
  editor: 'ckeditor',
  key,
  [RICH_TEXT_PROPERTY]: { format: 'html', kind },
});
const tag = (value: string, kind = 'BLOCK') => ({
  [RICH_TEXT_TAG]: { format: 'html', kind, value },
});

describe('tagRichText', () => {
  it('tags what an editor wrote with its format and kind, and leaves other fields alone', () => {
    const form = {
      components: [editor('besluit.motivation'), { type: 'textfield', key: 'besluit.kenmerk' }],
    };

    expect(
      tagRichText(form, {
        besluit: { motivation: '<p>Gegrond</p>', kenmerk: '<p>geen editor</p>' },
      }),
    ).toEqual({
      besluit: { motivation: tag('<p>Gegrond</p>'), kenmerk: '<p>geen editor</p>' },
    });
  });

  it('finds editors inside layout, the way the composer steps a form', () => {
    const form = {
      components: [
        {
          type: 'panel',
          components: [{ type: 'fieldset', components: [editor('aanhef', 'INLINE')] }],
        },
        { type: 'columns', columns: [{ components: [editor('slot')] }] },
      ],
    };

    expect(tagRichText(form, { aanhef: 'Geachte heer', slot: '<p>Groet</p>' })).toEqual({
      aanhef: tag('Geachte heer', 'INLINE'),
      slot: tag('<p>Groet</p>'),
    });
  });

  it('tags each row of a grid, whose children are keyed within the row', () => {
    const form = {
      components: [
        {
          type: 'datagrid',
          key: 'besluit.alineas',
          components: [editor('tekst'), { type: 'textfield', key: 'nummer' }],
        },
      ],
    };

    expect(
      tagRichText(form, {
        besluit: {
          alineas: [
            { tekst: '<p>Een</p>', nummer: '1' },
            { tekst: '<p>Twee</p>', nummer: '2' },
          ],
        },
      }),
    ).toEqual({
      besluit: {
        alineas: [
          { tekst: tag('<p>Een</p>'), nummer: '1' },
          { tekst: tag('<p>Twee</p>'), nummer: '2' },
        ],
      },
    });
  });

  it('tags each item of an editor that takes a list, empty ones included for the backend to drop', () => {
    const form = { components: [{ ...editor('alineas'), multiple: true }] };

    expect(tagRichText(form, { alineas: ['<p>Een</p>', ''] })).toEqual({
      alineas: [tag('<p>Een</p>'), tag('')],
    });
  });

  it('leaves what is not there absent, and what is tagged already as it is', () => {
    const form = { components: [editor('motivation'), editor('besluit.toelichting')] };
    const already = { motivation: tag('<p>eerder</p>') };

    expect(tagRichText(form, already)).toEqual(already);
    expect(tagRichText(form, {})).toEqual({});
  });

  it('does not change the data it was given', () => {
    const data = { motivation: '<p>Gegrond</p>' };

    tagRichText({ components: [editor('motivation')] }, data);

    expect(data).toEqual({ motivation: '<p>Gegrond</p>' });
  });

  it('returns the data as it is without a form', () => {
    const data = { motivation: '<p>Gegrond</p>' };

    expect(tagRichText(null, data)).toBe(data);
  });
});
