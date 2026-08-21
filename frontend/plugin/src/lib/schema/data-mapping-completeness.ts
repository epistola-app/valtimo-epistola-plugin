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

import { TemplateField } from '../models';
import {
  BuilderField,
  isBuilderCompatible,
  parseJsonataToBuilder,
} from '../utils/jsonata-converter';

export interface DataMappingCompleteness {
  staticallyAnalyzable: boolean;
  mappedRequiredFields: number;
  totalRequiredFields: number;
  missingRequiredFields: string[];
}

export function analyzeDataMappingCompleteness(
  expression: string | null | undefined,
  templateFields: TemplateField[],
): DataMappingCompleteness {
  if (!expression?.trim() || !isBuilderCompatible(expression)) {
    const requiredPaths = collectActiveRequiredPaths(templateFields, []);
    return {
      staticallyAnalyzable: !expression?.trim(),
      mappedRequiredFields: 0,
      totalRequiredFields: requiredPaths.length,
      missingRequiredFields: expression?.trim() ? [] : requiredPaths.map((path) => path.join('.')),
    };
  }

  const mappingFields = parseJsonataToBuilder(expression);
  const analysis = analyzeRequiredFields(templateFields, mappingFields);

  return {
    staticallyAnalyzable: true,
    mappedRequiredFields: analysis.total - analysis.missing.length,
    totalRequiredFields: analysis.total,
    missingRequiredFields: analysis.missing,
  };
}

function collectActiveRequiredPaths(fields: TemplateField[], parentPath: string[]): string[][] {
  return fields.flatMap((field) => {
    const path = [...parentPath, field.name];
    if (!field.required) return [];
    return [path, ...(field.complex ? [] : collectActiveRequiredPaths(field.children ?? [], path))];
  });
}

function analyzeRequiredFields(
  schemaFields: TemplateField[],
  mappingFields: BuilderField[],
  parentPath: string[] = [],
): { total: number; missing: string[] } {
  let total = 0;
  const missing: string[] = [];

  for (const schemaField of schemaFields) {
    const path = [...parentPath, schemaField.name];
    const mappingField = mappingFields.find((candidate) => candidate.name === schemaField.name);
    const fieldActive = schemaField.required || !!mappingField;

    if (schemaField.required) {
      total++;
      if (!mappingField) missing.push(path.join('.'));
    }

    if (schemaField.complex || !schemaField.children?.length || !fieldActive) continue;
    if (mappingField && !mappingField.children) {
      total += collectActiveRequiredPaths(schemaField.children, path).length;
      continue;
    }

    const nested = analyzeRequiredFields(schemaField.children, mappingField?.children ?? [], path);
    total += nested.total;
    missing.push(...nested.missing);
  }

  return { total, missing };
}
