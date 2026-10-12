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
import app.epistola.valtimo.expression.EpistolaExpressionFunction;
import app.epistola.valtimo.expression.ExpressionContext;

import java.util.Map;

/**
 * {@code $richText(value, format)} in a data mapping: read a case value as rich text in the named
 * format — {@code plain}, {@code html}, {@code markdown}, {@code prosemirror}, or one an
 * installation registers — and hand Epistola a document.
 *
 * <p>Needed only where a field's format differs from the configured default; a string mapped
 * straight onto a rich-text field is read in the default format without it. The result is a block
 * document, and a field that takes inline rich text narrows it to one paragraph when the data is
 * sent.
 */
public class RichTextFunction implements EpistolaExpressionFunction {

    private final RichTextConverter converter;

    public RichTextFunction(RichTextConverter converter) {
        this.converter = converter;
    }

    @Override
    public String name() {
        return "richText";
    }

    @Override
    public String description() {
        return "Read a value as rich text: $richText(value, format), where format is one of "
                + converter.formatIds() + "; without a format, the default ('"
                + converter.defaultFormatId() + "')";
    }

    /** The value in the default format. */
    public Map<String, Object> execute(ExpressionContext ctx, Object value) {
        return execute(ctx, value, null);
    }

    /** The value in the named format; nothing for nothing, so an absent field stays absent. */
    public Map<String, Object> execute(ExpressionContext ctx, Object value, String format) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            return null;
        }
        return converter.toDocument(value, format, RichTextKind.BLOCK);
    }
}
