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
import {
  BuilderField,
  isBuilderCompatible,
  isJsonataExpressionValid,
  parseJsonataToBuilder,
} from '../../utils/jsonata-converter';
import type { VariantSelectionMode } from './generate-document-config-editor.adapter';

export interface GenerateDocumentValidationOptions {
  selectedCatalogId?: string | null;
  dataMapping?: string | null;
  filename?: string | null;
  templateFields?: TemplateField[];
  templateFieldsReady?: boolean;
  variantSelectionMode?: VariantSelectionMode;
  variantAttributeEntries?: { key: string; value: string }[];
}

export interface DataMappingCompleteness {
  staticallyAnalyzable: boolean;
  mappedRequiredFields: number;
  totalRequiredFields: number;
  missingRequiredFields: string[];
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

export function analyzeDataMappingCompleteness(
  expression: string | null | undefined,
  templateFields: TemplateField[],
): DataMappingCompleteness {
  const requiredPaths = collectRequiredPaths(templateFields);
  if (!expression?.trim() || !isBuilderCompatible(expression)) {
    return {
      staticallyAnalyzable: !expression?.trim(),
      mappedRequiredFields: 0,
      totalRequiredFields: requiredPaths.length,
      missingRequiredFields: expression?.trim() ? [] : requiredPaths.map((path) => path.join('.')),
    };
  }

  const mappingFields = parseJsonataToBuilder(expression);
  const missingRequiredFields = requiredPaths
    .filter((path) => !isPathMapped(mappingFields, path))
    .map((path) => path.join('.'));

  return {
    staticallyAnalyzable: true,
    mappedRequiredFields: requiredPaths.length - missingRequiredFields.length,
    totalRequiredFields: requiredPaths.length,
    missingRequiredFields,
  };
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

function collectRequiredPaths(fields: TemplateField[], parentPath: string[] = []): string[][] {
  return fields.flatMap((field) => {
    const path = [...parentPath, field.name];
    return [...(field.required ? [path] : []), ...collectRequiredPaths(field.children ?? [], path)];
  });
}

function isPathMapped(fields: BuilderField[], path: string[]): boolean {
  const [head, ...tail] = path;
  const field = fields.find((candidate) => candidate.name === head);
  if (!field) {
    return false;
  }
  if (tail.length === 0 || !field.children) {
    return true;
  }
  return isPathMapped(field.children, tail);
}
