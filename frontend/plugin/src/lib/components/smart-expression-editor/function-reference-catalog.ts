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

import { ExpressionFunctionInfo } from '../../models';
import {
  FunctionSchemaField,
  expressionFunctionSchemaSources,
  expressionFunctionSignature,
} from '../../utils/expression-function-schema';
import { renderJsonataPathSegments } from '../../utils/jsonata-path';
import {
  ReferenceExpressionSegment,
  functionReferenceExpressionSegment,
} from './simple-jsonata-expression';

export interface FunctionReferenceOption extends ReferenceExpressionSegment {
  label: string;
  expression: string;
  description?: string;
  schemaField: FunctionSchemaField;
  insertable: boolean;
  expanded: boolean;
}

export interface FunctionReferenceGroup {
  id: string;
  signature: string;
  description: string;
  options: FunctionReferenceOption[];
}

export interface FunctionSchemaDiagnostic {
  signature: string;
  message: string;
}

interface FunctionReferenceSource {
  id: string;
  signature: string;
  description: string;
  options: FunctionReferenceOption[];
}

/**
 * Owns the schema-backed function picker model independently of editor DOM and caret state.
 */
export class FunctionReferenceCatalog {
  private readonly expandedFieldIds = new Set<string>();
  private sources: FunctionReferenceSource[] = [];
  private cachedQuery: string | null = null;
  private cachedGroups: FunctionReferenceGroup[] = [];

  public diagnostics: FunctionSchemaDiagnostic[] = [];

  public update(functions: ExpressionFunctionInfo[]): void {
    this.sources = expressionFunctionSchemaSources(functions || []).map((source) => ({
      id: source.id,
      signature: source.signature,
      description: source.description,
      options: source.fields.map((field) => {
        const segment = functionReferenceExpressionSegment(
          source.functionName,
          field.path,
          renderJsonataPathSegments(field.pathSegments),
        );
        return {
          kind: segment.kind,
          variable: segment.variable,
          path: segment.path,
          rootExpression: segment.rootExpression,
          pathExpression: segment.pathExpression,
          label: field.path,
          expression: field.expression,
          description: field.description,
          schemaField: field,
          insertable: field.insertable,
          expanded: this.expandedFieldIds.has(field.id),
        };
      }),
    }));
    this.diagnostics = (functions || []).flatMap((func) =>
      func.overloads.flatMap((overload) =>
        overload.schemaDiagnostic
          ? [
              {
                signature: expressionFunctionSignature(func.name, overload),
                message: overload.schemaDiagnostic.message,
              },
            ]
          : [],
      ),
    );

    const currentFieldIds = new Set(
      this.sources.flatMap((source) => source.options.map((option) => option.schemaField.id)),
    );
    for (const id of this.expandedFieldIds) {
      if (!currentFieldIds.has(id)) this.expandedFieldIds.delete(id);
    }
    for (const source of this.sources) {
      for (const option of source.options) {
        option.expanded = this.expandedFieldIds.has(option.schemaField.id);
      }
    }
    this.invalidate();
  }

  public groups(query: string): FunctionReferenceGroup[] {
    const normalizedQuery = query.trim().toLocaleLowerCase();
    if (normalizedQuery === this.cachedQuery) return this.cachedGroups;

    this.cachedQuery = normalizedQuery;
    this.cachedGroups = this.sources
      .map((source) => ({
        id: source.id,
        signature: source.signature,
        description: source.description,
        options: source.options.filter((option) =>
          normalizedQuery
            ? [option.label, option.expression, option.description || '', source.signature].some(
                (value) => value.toLocaleLowerCase().includes(normalizedQuery),
              )
            : option.schemaField.parentIds.every((parentId) => this.expandedFieldIds.has(parentId)),
        ),
      }))
      .filter((group) => group.options.length > 0);
    return this.cachedGroups;
  }

  public toggle(id: string): void {
    if (this.expandedFieldIds.has(id)) {
      this.expandedFieldIds.delete(id);
    } else {
      this.expandedFieldIds.add(id);
    }
    for (const source of this.sources) {
      for (const option of source.options) {
        if (option.schemaField.id === id) option.expanded = this.expandedFieldIds.has(id);
      }
    }
    this.invalidate();
  }

  private invalidate(): void {
    this.cachedQuery = null;
    this.cachedGroups = [];
  }
}
