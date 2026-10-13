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
package app.epistola.valtimo.richtext;

import app.epistola.valtimo.domain.TemplateField.RichTextKind;

import java.util.Map;

/**
 * The Form.io component a person types a rich-text format into, for the forms the plugin generates
 * (the letter composer, the retry form).
 *
 * <p>An extension point beside {@link RichTextFormat}, kept apart from it because the two change
 * for different reasons: a format changes with what a case stores, an editor with Form.io and its
 * editor builds. A format without an editor gets a plain text area. Key, label, validation and
 * value are filled in by the form generator; this supplies the component's type and settings.
 */
public interface RichTextEditor {

    /** The id of the format this editor writes, e.g. {@code html}. */
    String formatId();

    /** The component's type and settings for a field of this kind. */
    Map<String, Object> component(RichTextKind kind);
}
