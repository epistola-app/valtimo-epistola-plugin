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

import { JsonSchema, SimpleMappingSupport, TemplateField } from '../models';

export const FULL_SIMPLE_MAPPING_SUPPORT: SimpleMappingSupport = { level: 'FULL' };

export function supportsSimpleMapping(support: SimpleMappingSupport): boolean {
  return support.level !== 'UNSUPPORTED';
}

export function serializeTemplateSchema(schema: JsonSchema | boolean | null): string {
  return JSON.stringify(schema, null, 2) ?? '{}';
}

export function buildResolvedTemplateStructure(fields: TemplateField[], depth = 0): string {
  if (!fields?.length) return '{}';
  const indent = '  '.repeat(depth + 1);
  const closing = '  '.repeat(depth);

  const lines = fields.map((field) => {
    const required = field.required ? ' (required)' : '';
    const nullable = field.nullable ? ' (nullable)' : '';
    const complex = field.complex ? ' ⚠' : '';
    if (field.fieldType === 'OBJECT' && field.children?.length) {
      const nested = buildResolvedTemplateStructure(field.children, depth + 1);
      return `${indent}"${field.name}": ${nested}${required}${nullable}${complex}`;
    }
    if (field.fieldType === 'ARRAY' && field.children?.length) {
      const items = buildResolvedTemplateStructure(field.children, depth + 2);
      return `${indent}"${field.name}": [${items}]${required}${nullable}${complex}`;
    }
    return `${indent}"${field.name}": ${field.type || 'any'}${required}${nullable}${complex}`;
  });

  return `{\n${lines.join(',\n')}\n${closing}}`;
}
