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
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: EUPL-1.2
 */

import {
  buildGenerateDocumentConfig,
  buildValidateJsonataRequest,
  createVariantAttributeEditorEntries,
  formatVariantAttributes,
} from './generate-document-config-editor.adapter';

const baseInput = () => ({
  catalogId: 'catalog',
  templateId: 'template',
  resultProcessVariable: 'result',
  dataMapping: '{}',
  filenameExpression: '"letter.pdf"',
  correlationIdExpression: 'customer.id',
  environmentExpression: '"production"',
  variantSelectionMode: 'explicit' as const,
  variantExpression: '"formal"',
  variantAttributes: [],
});

describe('generate-document editor adapter', () => {
  it('preserves the canonical JSONata expressions selected by the editor', () => {
    expect(buildGenerateDocumentConfig(baseInput())).toEqual({
      actionConfigVersion: 1,
      catalogId: 'catalog',
      templateId: 'template',
      environmentId: '"production"',
      dataMapping: '{}',
      outputFormat: '"PDF"',
      filename: '"letter.pdf"',
      correlationId: 'customer.id',
      resultProcessVariable: 'result',
      variantId: '"formal"',
    });
  });

  it('serializes attribute selection without leaking editor state', () => {
    const input = {
      ...baseInput(),
      variantSelectionMode: 'attributes' as const,
      variantAttributes: [
        { key: 'language', value: '"nl"', required: true },
        { key: 'channel', value: 'customer.channel', required: false },
        { key: '', value: '', required: true, _customKey: true },
      ],
    };

    expect(buildGenerateDocumentConfig(input).variantAttributes).toEqual([
      { key: 'language', value: '"nl"', required: true },
      { key: 'channel', value: 'customer.channel', required: false },
    ]);
  });

  it('preserves persisted attribute expressions when creating editor entries', () => {
    expect(
      createVariantAttributeEditorEntries([
        { key: 'language', value: '"nl"', required: true },
        { key: 'channel', value: 'customer.channel', required: false },
      ]),
    ).toEqual([
      { key: 'language', value: '"nl"', required: true, _editorId: 'persisted-0' },
      {
        key: 'channel',
        value: 'customer.channel',
        required: false,
        _editorId: 'persisted-1',
      },
    ]);
  });

  it('builds the backend validation request from persisted configuration', () => {
    const config = buildGenerateDocumentConfig({
      ...baseInput(),
      variantSelectionMode: 'attributes',
      variantAttributes: [{ key: 'language', value: '"nl"', required: true }],
    });

    expect(buildValidateJsonataRequest(config)).toEqual({
      dataMapping: '{}',
      outputFormat: '"PDF"',
      filename: '"letter.pdf"',
      variantId: null,
      environmentId: '"production"',
      correlationId: 'customer.id',
      variantAttributeValues: { language: '"nl"' },
    });
  });

  it('formats variant attributes for option labels', () => {
    expect(formatVariantAttributes({ language: 'nl', channel: 'email' })).toBe(
      ' (language=nl, channel=email)',
    );
    expect(formatVariantAttributes({})).toBe('');
  });
});
