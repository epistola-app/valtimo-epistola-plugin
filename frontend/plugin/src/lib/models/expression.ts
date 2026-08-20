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

export interface ExpressionFunctionInfo {
  name: string;
  description: string;
  overloads: OverloadInfo[];
}

export interface OverloadInfo {
  arguments: ArgumentInfo[];
  returnType: string;
  resultSchema?: JsonSchema | boolean | null;
  schemaDiagnostic?: SchemaDiagnostic | null;
}

export interface ArgumentInfo {
  name: string;
  type: string;
}

export interface SchemaDiagnostic {
  code: string;
  message: string;
}

export interface JsonSchema {
  $ref?: string;
  $defs?: Record<string, JsonSchema | boolean>;
  definitions?: Record<string, JsonSchema | boolean>;
  type?: string | string[];
  description?: string;
  properties?: Record<string, JsonSchema | boolean>;
  required?: string[];
  items?: JsonSchema | boolean;
  allOf?: Array<JsonSchema | boolean>;
  anyOf?: Array<JsonSchema | boolean>;
  oneOf?: Array<JsonSchema | boolean>;
}
