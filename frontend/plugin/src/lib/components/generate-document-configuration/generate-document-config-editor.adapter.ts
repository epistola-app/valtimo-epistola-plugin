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

import { SelectItem } from '@valtimo/components';
import {
  GenerateDocumentConfig,
  ValidateJsonataRequest,
  VariantAttributeEntry,
} from '../../models';
import {
  decodeJsonataStringLiteral,
  encodeJsonataStringLiteral,
} from './generate-document-config-version';

export type VariantSelectionMode = 'explicit' | 'attributes';

export interface VariantAttributeEditorEntry extends VariantAttributeEntry {
  _customKey?: boolean;
  _expressionMode?: boolean;
}

export interface ExpressionSelectPrefill {
  expressionMode: boolean;
  expression: string;
  value: string;
}

export interface BuildGenerateDocumentConfigInput {
  catalogId: string;
  templateId: string;
  resultProcessVariable: string;
  dataMapping: string;
  filenameExpression: string;
  correlationIdExpression: string;
  environment: {
    expressionMode: boolean;
    expression: string;
    value: string;
  };
  variantSelectionMode: VariantSelectionMode;
  variant: {
    expressionMode: boolean;
    expression: string;
    value: string;
  };
  variantAttributes: VariantAttributeEditorEntry[];
}

export function resolveExpressionSelectPrefill(
  expression: string | undefined,
  options: SelectItem[],
): ExpressionSelectPrefill {
  if (!expression) {
    return { expressionMode: false, expression: '', value: '' };
  }

  const literal = decodeJsonataStringLiteral(expression);
  const exactMatch =
    literal !== undefined && options.some((option) => String(option.id) === literal);

  return exactMatch
    ? { expressionMode: false, expression: '', value: literal }
    : { expressionMode: true, expression, value: '' };
}

export function createVariantAttributeEditorEntries(
  attributes: VariantAttributeEntry[] | undefined,
): VariantAttributeEditorEntry[] {
  return (attributes ?? []).map((attribute) => {
    const literal = decodeJsonataStringLiteral(attribute.value);
    return {
      ...attribute,
      value: literal ?? attribute.value,
      _expressionMode: literal === undefined,
    };
  });
}

export function buildGenerateDocumentConfig(
  input: BuildGenerateDocumentConfigInput,
): GenerateDocumentConfig {
  const config: GenerateDocumentConfig = {
    actionConfigVersion: 1,
    catalogId: input.catalogId,
    templateId: input.templateId,
    environmentId: expressionSelectValue(input.environment),
    dataMapping: input.dataMapping,
    outputFormat: encodeJsonataStringLiteral('PDF'),
    filename: input.filenameExpression,
    correlationId: input.correlationIdExpression || undefined,
    resultProcessVariable: input.resultProcessVariable,
  };

  if (input.variantSelectionMode === 'explicit') {
    config.variantId = expressionSelectValue(input.variant);
  } else {
    config.variantAttributes = input.variantAttributes
      .filter((entry) => entry.key && entry.value)
      .map((entry) => ({
        key: entry.key,
        value: entry._expressionMode ? entry.value : encodeJsonataStringLiteral(entry.value),
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

function expressionSelectValue(value: {
  expressionMode: boolean;
  expression: string;
  value: string;
}): string | undefined {
  if (value.expressionMode) {
    return value.expression || undefined;
  }
  return value.value ? encodeJsonataStringLiteral(value.value) : undefined;
}
