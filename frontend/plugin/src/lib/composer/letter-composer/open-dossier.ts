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

/**
 * Which dossier the user currently has open, read from the route.
 *
 * <p>A start form is the one place the case cannot come from a task, and Valtimo does not prefill
 * value-resolver fields on its `start-form?documentId=` route, so the server-prefilled carrier
 * arrives empty there. The route does say which case is on screen, and using it is sound for the
 * same reason ADR 0004 gives for the start-event preview: the id selects *which* case is checked,
 * never *whether* it is — the backend still requires permission to start the process and to view
 * that case before it reads a single field.
 */
const DOSSIER_PATH = /\/document\/([0-9a-fA-F-]{36})(?:\/|$)/;

export function readOpenDossierId(pathname: string | null | undefined): string | null {
  const match = DOSSIER_PATH.exec(pathname ?? '');
  return match ? match[1] : null;
}
