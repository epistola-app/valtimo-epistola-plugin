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

import { ExpressionFunctionInfo, JsonSchema, OverloadInfo } from '../models';
import { renderJsonataPathTail } from './jsonata-path';

const MAX_SCHEMA_DEPTH = 12;

export interface FunctionSchemaField {
  id: string;
  parentIds: string[];
  name: string;
  path: string;
  expression: string;
  depth: number;
  type: string;
  description?: string;
  required: boolean;
  nullable: boolean;
  expandable: boolean;
  insertable: boolean;
}

export interface FunctionSchemaSource {
  id: string;
  functionName: string;
  signature: string;
  description: string;
  fields: FunctionSchemaField[];
}

export function expressionFunctionSchemaSources(
  functions: ExpressionFunctionInfo[],
): FunctionSchemaSource[] {
  return functions.flatMap((func) =>
    func.overloads.flatMap((overload, overloadIndex) => {
      if (!isSchemaObject(overload.resultSchema)) return [];
      const sourceId = `${func.name}:${overloadIndex}`;
      return [
        {
          id: sourceId,
          functionName: func.name,
          signature: functionSignature(func.name, overload),
          description: func.description,
          fields: flattenObjectProperties(
            overload.resultSchema,
            overload.resultSchema,
            `$${func.name}()`,
            sourceId,
            overload.arguments.length === 0,
          ),
        },
      ];
    }),
  );
}

function flattenObjectProperties(
  schema: JsonSchema,
  rootSchema: JsonSchema,
  rootExpression: string,
  sourceId: string,
  insertable: boolean,
  parentPath = '',
  parentIds: string[] = [],
  depth = 0,
): FunctionSchemaField[] {
  const resolved = effectiveSchema(schema, rootSchema);
  const required = new Set(resolved.required || []);
  return Object.entries(resolved.properties || {}).flatMap(([name, childValue]) => {
    if (!isSchemaObject(childValue)) return [];
    const child = effectiveSchema(childValue, rootSchema);
    const arrayItems =
      isArraySchema(child) && isSchemaObject(child.items)
        ? effectiveSchema(child.items, rootSchema)
        : null;
    const nestedSchema = arrayItems || child;
    const path = parentPath ? `${parentPath}.${name}` : name;
    const id = `${sourceId}:${path}`;
    const expandable =
      depth < MAX_SCHEMA_DEPTH && !!Object.keys(nestedSchema.properties || {}).length;
    const field: FunctionSchemaField = {
      id,
      parentIds,
      name,
      path,
      expression: `${rootExpression}.${renderJsonataPathTail(path)}`,
      depth,
      type: schemaTypeLabel(child, arrayItems),
      description: child.description,
      required: required.has(name),
      nullable: isNullable(child),
      expandable,
      insertable,
    };
    if (!expandable) return [field];
    return [
      field,
      ...flattenObjectProperties(
        nestedSchema,
        rootSchema,
        rootExpression,
        sourceId,
        insertable,
        arrayItems ? `${path}[]` : path,
        [...parentIds, id],
        depth + 1,
      ),
    ];
  });
}

function effectiveSchema(schema: JsonSchema, rootSchema: JsonSchema): JsonSchema {
  const referenced = schema.$ref ? resolveLocalReference(rootSchema, schema.$ref) : schema;
  const variants = [
    ...(referenced.allOf || []),
    ...(referenced.anyOf || []),
    ...(referenced.oneOf || []),
  ]
    .filter(isSchemaObject)
    .map((variant) => effectiveSchema(variant, rootSchema));
  return variants.reduce(
    (combined, variant) => ({
      ...combined,
      ...variant,
      type: combined.type || variant.type,
      description: combined.description || variant.description,
      properties: { ...combined.properties, ...variant.properties },
      required: [...new Set([...(combined.required || []), ...(variant.required || [])])],
    }),
    referenced,
  );
}

function resolveLocalReference(rootSchema: JsonSchema, reference: string): JsonSchema {
  if (!reference.startsWith('#/')) return {};
  const resolved = reference
    .slice(2)
    .split('/')
    .map((segment) => segment.replace(/~1/g, '/').replace(/~0/g, '~'))
    .reduce<unknown>(
      (value, segment) =>
        value && typeof value === 'object'
          ? (value as Record<string, unknown>)[segment]
          : undefined,
      rootSchema,
    );
  return isSchemaObject(resolved) ? resolved : {};
}

function functionSignature(name: string, overload: OverloadInfo): string {
  const argumentsLabel = overload.arguments
    .map((argument) => `${argument.name}: ${argument.type}`)
    .join(', ');
  return `$${name}(${argumentsLabel})`;
}

function schemaTypeLabel(schema: JsonSchema, arrayItems: JsonSchema | null): string {
  if (arrayItems) return `array<${primaryType(arrayItems)}>`;
  return primaryType(schema);
}

function primaryType(schema: JsonSchema): string {
  const types = Array.isArray(schema.type) ? schema.type : schema.type ? [schema.type] : [];
  const nonNullType = types.find((type) => type !== 'null');
  if (nonNullType) return nonNullType;
  if (schema.properties) return 'object';
  return 'value';
}

function isArraySchema(schema: JsonSchema): boolean {
  return schema.type === 'array' || (Array.isArray(schema.type) && schema.type.includes('array'));
}

function isNullable(schema: JsonSchema): boolean {
  const types = Array.isArray(schema.type) ? schema.type : schema.type ? [schema.type] : [];
  if (types.includes('null')) return true;
  return [...(schema.anyOf || []), ...(schema.oneOf || [])].some(
    (variant) => isSchemaObject(variant) && isNullable(variant),
  );
}

function isSchemaObject(value: unknown): value is JsonSchema {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}
