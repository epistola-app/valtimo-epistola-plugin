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

const BARE_PATH_SEGMENT = /^[A-Za-z_][A-Za-z0-9_]*(\[\d*\])*$/;

/**
 * Render a JSONata path tail for a Formio-style dotted key.
 *
 * Formio stores component keys through lodash get/set semantics, so dots in a
 * component key are path separators. JSONata backticks should therefore quote
 * only the segment that needs it, not the whole dotted key.
 */
export function renderJsonataPathTail(key: string): string {
  return key
    .split('.')
    .map((segment) => (BARE_PATH_SEGMENT.test(segment) ? segment : '`' + segment + '`'))
    .join('.');
}

export function renderJsonataPath(variableName: string, key: string): string {
  return `$${variableName}.${renderJsonataPathTail(key)}`;
}
