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

import * as _jsonata from 'jsonata';

const jsonata = (_jsonata as any).default || _jsonata;

export function encodeJsonataStringLiteral(value: string): string {
  return JSON.stringify(value);
}

export function decodeJsonataStringLiteral(expression: string): string | undefined {
  try {
    const ast = (jsonata(expression) as any).ast();
    return ast?.type === 'string' && typeof ast.value === 'string' ? ast.value : undefined;
  } catch {
    return undefined;
  }
}
