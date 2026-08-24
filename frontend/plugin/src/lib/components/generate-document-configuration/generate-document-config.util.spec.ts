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
  analyzeDataMappingCompleteness,
  isGenerateDocumentConfigValid,
  isProcessVariableNameValid,
} from './generate-document-config.util';
import type { GenerateDocumentValidationOptions } from './generate-document-config.util';
import type { GenerateDocumentConfig } from '../../models';
import type { TemplateField } from '../../models';

describe('generate-document-config.util', () => {
  describe('isProcessVariableNameValid', () => {
    it.each(['epistolaResult', 'resultA', 'requestId1', 'ABC123'])(
      'accepts alphanumeric value %s',
      (value) => {
        expect(isProcessVariableNameValid(value)).toBe(true);
      },
    );

    const missingValue = ({} as Record<string, unknown>)['missing'];

    it.each([
      'pv:some-value',
      'some-value',
      'some_value',
      'some.value',
      '',
      ' ',
      missingValue,
      null,
    ])('rejects invalid value %s', (value) => {
      expect(isProcessVariableNameValid(value)).toBe(false);
    });
  });

  describe('isGenerateDocumentConfigValid', () => {
    const validConfig = {
      templateId: 'template',
      resultProcessVariable: 'epistolaResult',
    };
    const validOptions = {
      selectedCatalogId: 'catalog',
      dataMapping: '{}',
      filename: 'document.pdf',
      templateFields: [] as TemplateField[],
      templateFieldsReady: true,
      variantSelectionMode: 'explicit' as const,
    };
    const config = (patch: Partial<GenerateDocumentConfig>): Partial<GenerateDocumentConfig> =>
      Object.assign({}, validConfig, patch);
    const options = (patch: GenerateDocumentValidationOptions): GenerateDocumentValidationOptions =>
      Object.assign({}, validOptions, patch);

    it('accepts a complete config with an alphanumeric result process variable', () => {
      expect(isGenerateDocumentConfigValid(validConfig, validOptions)).toBe(true);
    });

    it('rejects a complete config with an invalid result process variable', () => {
      expect(
        isGenerateDocumentConfigValid(
          config({ resultProcessVariable: 'pv:some-value' }),
          validOptions,
        ),
      ).toBe(false);
    });

    it('preserves required field validation', () => {
      expect(isGenerateDocumentConfigValid(null, validOptions)).toBe(false);
      expect(isGenerateDocumentConfigValid(config({ templateId: '' }), validOptions)).toBe(false);
      expect(isGenerateDocumentConfigValid(validConfig, options({ selectedCatalogId: '' }))).toBe(
        false,
      );
      expect(isGenerateDocumentConfigValid(validConfig, options({ filename: '' }))).toBe(false);
      expect(isGenerateDocumentConfigValid(validConfig, options({ dataMapping: '' }))).toBe(false);
    });

    it('requires attribute rows to be complete in attribute selection mode', () => {
      expect(
        isGenerateDocumentConfigValid(
          validConfig,
          options({
            variantSelectionMode: 'attributes',
            variantAttributeEntries: [{ key: 'language', value: 'nl' }],
          }),
        ),
      ).toBe(true);
      expect(
        isGenerateDocumentConfigValid(
          validConfig,
          options({
            variantSelectionMode: 'attributes',
            variantAttributeEntries: [{ key: 'language', value: '' }],
          }),
        ),
      ).toBe(false);
    });

    it('requires every schema-required field in a Simple-compatible mapping', () => {
      const templateFields: TemplateField[] = [
        {
          name: 'requiredValue',
          path: 'requiredValue',
          type: 'string',
          fieldType: 'SCALAR',
          required: true,
        },
        {
          name: 'optionalValue',
          path: 'optionalValue',
          type: 'string',
          fieldType: 'SCALAR',
          required: false,
        },
      ];

      expect(
        isGenerateDocumentConfigValid(validConfig, options({ dataMapping: '{}', templateFields })),
      ).toBe(false);
      expect(
        isGenerateDocumentConfigValid(
          validConfig,
          options({
            dataMapping: '{"requiredValue": $doc.value}',
            templateFields,
          }),
        ),
      ).toBe(true);
    });

    it('allows an empty object when the template has no required fields', () => {
      const templateFields: TemplateField[] = [
        {
          name: 'optionalValue',
          path: 'optionalValue',
          type: 'string',
          fieldType: 'SCALAR',
          required: false,
        },
      ];

      expect(
        isGenerateDocumentConfigValid(validConfig, options({ dataMapping: '{}', templateFields })),
      ).toBe(true);
    });

    it('checks nested required paths and accepts a direct ancestor expression', () => {
      const templateFields: TemplateField[] = [
        {
          name: 'customer',
          path: 'customer',
          type: 'object',
          fieldType: 'OBJECT',
          required: true,
          children: [
            {
              name: 'name',
              path: 'customer.name',
              type: 'string',
              fieldType: 'SCALAR',
              required: true,
            },
          ],
        },
      ];

      expect(
        analyzeDataMappingCompleteness('{"customer": {}}', templateFields).missingRequiredFields,
      ).toEqual(['customer.name']);
      expect(
        isGenerateDocumentConfigValid(
          validConfig,
          options({ dataMapping: '{"customer": $doc.customer}', templateFields }),
        ),
      ).toBe(true);
    });

    it('requires nested fields only when an optional object is constructed', () => {
      const templateFields: TemplateField[] = [
        {
          name: 'address',
          path: 'address',
          type: 'object',
          fieldType: 'OBJECT',
          required: false,
          children: [
            {
              name: 'street',
              path: 'address.street',
              type: 'string',
              fieldType: 'SCALAR',
              required: true,
            },
            {
              name: 'city',
              path: 'address.city',
              type: 'string',
              fieldType: 'SCALAR',
              required: true,
            },
          ],
        },
      ];

      expect(analyzeDataMappingCompleteness('{}', templateFields)).toMatchObject({
        totalRequiredFields: 0,
        missingRequiredFields: [],
      });
      expect(
        analyzeDataMappingCompleteness('{"address": {"street": $doc.street}}', templateFields),
      ).toMatchObject({
        totalRequiredFields: 2,
        mappedRequiredFields: 1,
        missingRequiredFields: ['address.city'],
      });
      expect(
        analyzeDataMappingCompleteness('{"address": $doc.address}', templateFields),
      ).toMatchObject({
        totalRequiredFields: 2,
        mappedRequiredFields: 2,
        missingRequiredFields: [],
      });
    });

    it('validates a complex whole-value field without inspecting its child shape', () => {
      const templateFields: TemplateField[] = [
        {
          name: 'subjects',
          path: 'subjects',
          type: 'array<object>',
          fieldType: 'ARRAY',
          required: true,
          complex: true,
          children: [
            {
              name: 'name',
              path: 'subjects[].name',
              type: 'string',
              fieldType: 'SCALAR',
              required: true,
            },
          ],
        },
      ];

      expect(analyzeDataMappingCompleteness('{}', templateFields)).toMatchObject({
        totalRequiredFields: 1,
        mappedRequiredFields: 0,
        missingRequiredFields: ['subjects'],
      });
      expect(
        analyzeDataMappingCompleteness('{"subjects": $doc.subjects}', templateFields),
      ).toMatchObject({
        totalRequiredFields: 1,
        mappedRequiredFields: 1,
        missingRequiredFields: [],
      });
    });

    it('accepts Advanced-only mappings without static schema completeness checks', () => {
      const templateFields: TemplateField[] = [
        {
          name: 'requiredValue',
          path: 'requiredValue',
          type: 'string',
          fieldType: 'SCALAR',
          required: true,
        },
      ];

      expect(
        isGenerateDocumentConfigValid(
          validConfig,
          options({
            dataMapping: '$merge([$doc.payload, $pv.overrides])',
            templateFields,
            templateFieldsReady: false,
          }),
        ),
      ).toBe(true);
    });

    it('rejects invalid Advanced JSONata even when it cannot be analyzed statically', () => {
      expect(
        isGenerateDocumentConfigValid(
          validConfig,
          options({ dataMapping: '$merge([', templateFieldsReady: false }),
        ),
      ).toBe(false);
    });

    it('blocks statically analyzable mappings until the template schema is available', () => {
      expect(
        isGenerateDocumentConfigValid(
          validConfig,
          options({ dataMapping: '{}', templateFieldsReady: false }),
        ),
      ).toBe(false);
    });
  });
});
