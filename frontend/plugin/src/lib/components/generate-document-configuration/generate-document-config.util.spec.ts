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
  isGenerateDocumentConfigValid,
  isProcessVariableNameValid,
} from './generate-document-config.util';
import type { GenerateDocumentValidationOptions } from './generate-document-config.util';
import type { GenerateDocumentConfig } from '../../models';

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
      outputFormat: 'PDF' as const,
      resultProcessVariable: 'epistolaResult',
    };
    const validOptions = {
      selectedCatalogId: 'catalog',
      filename: 'document.pdf',
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
      expect(
        isGenerateDocumentConfigValid(
          { templateId: 'template', resultProcessVariable: 'epistolaResult' },
          validOptions,
        ),
      ).toBe(false);
      expect(isGenerateDocumentConfigValid(validConfig, options({ selectedCatalogId: '' }))).toBe(
        false,
      );
      expect(isGenerateDocumentConfigValid(validConfig, options({ filename: '' }))).toBe(false);
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
  });
});
