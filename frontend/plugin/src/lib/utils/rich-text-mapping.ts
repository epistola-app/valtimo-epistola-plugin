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
 * Whether a mapping for a rich-text field should be told about `$richText` (ADR 0008).
 *
 * A rich-text field takes a document. A mapping produces one by naming the format the case keeps
 * the text in, `$richText($doc.x, "html")`; a bare text is sent as it is, and Epistola refuses it.
 * The plugin does not guess the format on the author's behalf, so the builder says so instead.
 *
 * Shown only once something is mapped: an empty field has nothing to wrap yet. A mapping that
 * already calls `$richText` anywhere is left alone, as is a field that is not rich text.
 */
export function needsRichTextHint(field: { richText?: boolean; value?: string }): boolean {
  const value = field.value?.trim() ?? '';
  return !!field.richText && value !== '' && !/\$richText\s*\(/.test(value);
}
