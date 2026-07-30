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
  canRepresentExpressionAsSelection,
  createVariantAttributeEditorEntries,
  formatVariantAttributes,
  resolveExpressionSelectPrefill,
} from './generate-document-config-editor.adapter';

const baseInput = () => ({
  catalogId: 'catalog',
  templateId: 'template',
  resultProcessVariable: 'result',
  dataMapping: '{}',
  filenameExpression: '"letter.pdf"',
  correlationIdExpression: 'customer.id',
  environment: {
    expressionMode: false,
    expression: '',
    value: 'production',
  },
  variantSelectionMode: 'explicit' as const,
  variant: {
    expressionMode: false,
    expression: '',
    value: 'formal',
  },
  variantAttributes: [],
});

describe('generate-document editor adapter', () => {
  it('prefills a selector only for an exact JSONata string literal match', () => {
    const options = [
      { id: 'default', text: 'Default' },
      { id: 'production', text: 'Production' },
    ];

    expect(resolveExpressionSelectPrefill('"production"', options)).toEqual({
      expressionMode: false,
      expression: '',
      value: 'production',
    });
    expect(resolveExpressionSelectPrefill('customer.environment', options)).toEqual({
      expressionMode: true,
      expression: 'customer.environment',
      value: '',
    });
    expect(resolveExpressionSelectPrefill('"missing"', options)).toEqual({
      expressionMode: true,
      expression: '"missing"',
      value: '',
    });
  });

  it('allows switching to a dropdown only for empty or exactly matching literal expressions', () => {
    const options = [{ id: 'production', text: 'Production' }];

    expect(canRepresentExpressionAsSelection('', options)).toBe(true);
    expect(canRepresentExpressionAsSelection('"production"', options)).toBe(true);
    expect(canRepresentExpressionAsSelection('"removed"', options)).toBe(false);
    expect(canRepresentExpressionAsSelection('customer.environment', options)).toBe(false);
  });

  it('serializes dropdown values and direct expressions to v1 JSONata', () => {
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
      { key: 'language', value: '"nl"', required: true },
      {
        key: 'channel',
        value: 'customer.channel',
        required: false,
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
