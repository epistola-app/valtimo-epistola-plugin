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

import { GenerateDocumentConfig, TemplateField } from '../../models';
import { isJsonataExpressionValid } from '../../utils/jsonata-converter';
import { analyzeDataMappingCompleteness } from '../../schema/data-mapping-completeness';
import type { DataMappingCompleteness } from '../../schema/data-mapping-completeness';
import type { VariantSelectionMode } from './generate-document-config-editor.adapter';

export { analyzeDataMappingCompleteness };
export type { DataMappingCompleteness };

export interface GenerateDocumentValidationOptions {
  selectedCatalogId?: string | null;
  dataMapping?: string | null;
  filename?: string | null;
  templateFields?: TemplateField[];
  templateFieldsReady?: boolean;
  variantSelectionMode?: VariantSelectionMode;
  variantAttributeEntries?: { key: string; value: string }[];
}

export const PROCESS_VARIABLE_NAME_PATTERN = /^[A-Za-z0-9]+$/;

export function isProcessVariableNameValid(value: unknown): boolean {
  return typeof value === 'string' && PROCESS_VARIABLE_NAME_PATTERN.test(value);
}

export function isGenerateDocumentConfigValid(
  config: Partial<GenerateDocumentConfig> | null | undefined,
  options: GenerateDocumentValidationOptions,
): boolean {
  const baseComplete = !!(
    options.selectedCatalogId &&
    config &&
    config.templateId &&
    options.dataMapping &&
    options.dataMapping.trim() &&
    options.filename &&
    options.filename.trim() &&
    isProcessVariableNameValid(config.resultProcessVariable)
  );

  let variantValid = true;
  if (
    options.variantSelectionMode === 'attributes' &&
    options.variantAttributeEntries &&
    options.variantAttributeEntries.length > 0
  ) {
    variantValid = options.variantAttributeEntries.every((entry) => !!entry.key && !!entry.value);
  }

  const mappingComplete = isDataMappingComplete(
    options.dataMapping,
    options.templateFields ?? [],
    options.templateFieldsReady ?? true,
  );

  return baseComplete && mappingComplete && variantValid;
}

function isDataMappingComplete(
  expression: string | null | undefined,
  templateFields: TemplateField[],
  templateFieldsReady: boolean,
): boolean {
  if (!expression?.trim()) {
    return false;
  }
  if (!isJsonataExpressionValid(expression)) {
    return false;
  }

  const completeness = analyzeDataMappingCompleteness(expression, templateFields);
  if (!completeness.staticallyAnalyzable) {
    return true;
  }
  return templateFieldsReady && completeness.missingRequiredFields.length === 0;
}
