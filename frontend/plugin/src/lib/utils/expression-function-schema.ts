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
import { JsonataPathSegment, renderJsonataPathSegments } from './jsonata-path';

const MAX_SCHEMA_DEPTH = 12;

export interface FunctionSchemaField {
  id: string;
  parentIds: string[];
  name: string;
  path: string;
  pathSegments: JsonataPathSegment[];
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
          signature: expressionFunctionSignature(func.name, overload),
          description: func.description,
          fields: flattenObjectProperties(
            overload.resultSchema,
            overload.resultSchema,
            `$${func.name}()`,
            sourceId,
            overload.arguments.length === 0,
            [],
            new Set(),
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
  pathSegments: JsonataPathSegment[],
  referenceStack: Set<string>,
  parentIds: string[] = [],
  depth = 0,
): FunctionSchemaField[] {
  const resolved = effectiveSchema(schema, rootSchema, referenceStack);
  const required = new Set(resolved.required || []);
  return Object.entries(resolved.properties || {}).flatMap(([name, childValue]) => {
    if (!isSchemaObject(childValue)) return [];
    const child = effectiveSchema(childValue, rootSchema, resolved.referenceStack);
    const arrayItems =
      isArraySchema(child) && isSchemaObject(child.items)
        ? effectiveSchema(child.items, rootSchema, child.referenceStack)
        : null;
    const nestedSchema = arrayItems || child;
    const fieldSegments = [...pathSegments, { name }];
    const path = displayPath(fieldSegments);
    const id = `${sourceId}:${fieldSegments.map((segment) => jsonPointerSegment(segment.name)).join('/')}`;
    const expandable =
      depth < MAX_SCHEMA_DEPTH && !!Object.keys(nestedSchema.properties || {}).length;
    const field: FunctionSchemaField = {
      id,
      parentIds,
      name,
      path,
      pathSegments: fieldSegments,
      expression: `${rootExpression}.${renderJsonataPathSegments(fieldSegments)}`,
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
        arrayItems ? [...pathSegments, { name, array: true }] : fieldSegments,
        nestedSchema.referenceStack,
        [...parentIds, id],
        depth + 1,
      ),
    ];
  });
}

interface EffectiveSchema extends JsonSchema {
  referenceStack: Set<string>;
}

function effectiveSchema(
  schema: JsonSchema,
  rootSchema: JsonSchema,
  referenceStack: Set<string>,
): EffectiveSchema {
  let referenced = schema;
  let nextReferenceStack = referenceStack;
  if (schema.$ref) {
    if (referenceStack.has(schema.$ref)) return { referenceStack };
    referenced = resolveLocalReference(rootSchema, schema.$ref);
    nextReferenceStack = new Set(referenceStack).add(schema.$ref);
  }

  let combined: EffectiveSchema = {
    ...referenced,
    properties: { ...(referenced.properties || {}) },
    required: [...(referenced.required || [])],
    referenceStack: nextReferenceStack,
  };

  for (const variant of (referenced.allOf || []).filter(isSchemaObject)) {
    combined = mergeConjunctive(combined, effectiveSchema(variant, rootSchema, nextReferenceStack));
  }

  const alternatives = [...(referenced.anyOf || []), ...(referenced.oneOf || [])]
    .filter(isSchemaObject)
    .map((variant) => effectiveSchema(variant, rootSchema, nextReferenceStack))
    .filter(isObjectShape);
  if (alternatives.length) {
    const alternativeRequired = alternatives
      .map((variant) => new Set(variant.required || []))
      .reduce(
        (intersection, required) => new Set([...intersection].filter((name) => required.has(name))),
      );
    combined = {
      ...combined,
      properties: Object.assign(
        {},
        combined.properties,
        ...alternatives.map((item) => item.properties),
      ),
      required: [...new Set([...(combined.required || []), ...alternativeRequired])],
      type: combined.type || alternatives[0].type,
      description: combined.description || alternatives[0].description,
    };
  }
  return combined;
}

function mergeConjunctive(left: EffectiveSchema, right: EffectiveSchema): EffectiveSchema {
  return {
    ...left,
    type: left.type || right.type,
    description: left.description || right.description,
    items: left.items || right.items,
    properties: { ...left.properties, ...right.properties },
    required: [...new Set([...(left.required || []), ...(right.required || [])])],
    referenceStack: new Set([...left.referenceStack, ...right.referenceStack]),
  };
}

function isObjectShape(schema: JsonSchema): boolean {
  const types = Array.isArray(schema.type) ? schema.type : schema.type ? [schema.type] : [];
  return types.includes('object') || !!schema.properties;
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

function displayPath(segments: JsonataPathSegment[]): string {
  return segments.map((segment) => `${segment.name}${segment.array ? '[]' : ''}`).join('.');
}

function jsonPointerSegment(segment: string): string {
  return segment.replace(/~/g, '~0').replace(/\//g, '~1');
}

export function expressionFunctionSignature(name: string, overload: OverloadInfo): string {
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
