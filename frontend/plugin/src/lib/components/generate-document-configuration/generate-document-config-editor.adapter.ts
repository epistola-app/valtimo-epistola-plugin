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
  GenerateDocumentConfig,
  ValidateJsonataRequest,
  VariantAttributeEntry,
} from '../../models';
import { encodeJsonataStringLiteral } from './generate-document-config-version';

export type VariantSelectionMode = 'explicit' | 'attributes';

export interface VariantAttributeEditorEntry extends VariantAttributeEntry {
  _customKey?: boolean;
  _editorId?: string;
}

export interface BuildGenerateDocumentConfigInput {
  catalogId: string;
  templateId: string;
  resultProcessVariable: string;
  dataMapping: string;
  filenameExpression: string;
  correlationIdExpression: string;
  environmentExpression: string;
  variantSelectionMode: VariantSelectionMode;
  variantExpression: string;
  variantAttributes: VariantAttributeEditorEntry[];
}

export function createVariantAttributeEditorEntries(
  attributes: VariantAttributeEntry[] | undefined,
): VariantAttributeEditorEntry[] {
  return (attributes ?? []).map((attribute, index) => ({
    ...attribute,
    _editorId: `persisted-${index}`,
  }));
}

export function buildGenerateDocumentConfig(
  input: BuildGenerateDocumentConfigInput,
): GenerateDocumentConfig {
  const config: GenerateDocumentConfig = {
    actionConfigVersion: 1,
    catalogId: input.catalogId,
    templateId: input.templateId,
    environmentId: input.environmentExpression || undefined,
    dataMapping: input.dataMapping,
    outputFormat: encodeJsonataStringLiteral('PDF'),
    filename: input.filenameExpression,
    correlationId: input.correlationIdExpression || undefined,
    resultProcessVariable: input.resultProcessVariable,
  };

  if (input.variantSelectionMode === 'explicit') {
    config.variantId = input.variantExpression || undefined;
  } else {
    config.variantAttributes = input.variantAttributes
      .filter((entry) => entry.key && entry.value)
      .map((entry) => ({
        key: entry.key,
        value: entry.value,
        required: entry.required,
      }));
  }

  return config;
}

export function buildValidateJsonataRequest(
  config: GenerateDocumentConfig,
): ValidateJsonataRequest {
  const variantAttributeValues = Object.fromEntries(
    (config.variantAttributes ?? []).map((attribute) => [attribute.key, attribute.value]),
  );

  return {
    dataMapping: config.dataMapping || null,
    outputFormat: config.outputFormat,
    filename: config.filename,
    variantId: config.variantId || null,
    environmentId: config.environmentId || null,
    correlationId: config.correlationId || null,
    variantAttributeValues:
      Object.keys(variantAttributeValues).length > 0 ? variantAttributeValues : null,
  };
}

export function formatVariantAttributes(attributes: Record<string, string>): string {
  const entries = Object.entries(attributes || {});
  if (entries.length === 0) {
    return '';
  }
  return ` (${entries.map(([key, value]) => `${key}=${value}`).join(', ')})`;
}
