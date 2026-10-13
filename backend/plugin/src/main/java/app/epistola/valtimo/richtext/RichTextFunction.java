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
import lombok.extern.slf4j.Slf4j;

import java.util.Locale;
import java.util.Map;

/**
 * {@code $richText(value, format)} in a data mapping: read a case value as rich text in the named
 * format — {@code plain}, {@code html}, {@code markdown}, {@code prosemirror}, or one an
 * installation registers — and hand Epistola a document.
 *
 * <p>How a mapping fills a rich-text field (ADR 0008): only its author knows the case's format, so
 * the author says it here. A string mapped straight onto a rich-text field is sent as it is, and
 * Epistola refuses it. Without a format, the configured default is read.
 *
 * <p>The result is a block document. A field that takes inline rich text accepts it while it holds
 * one paragraph; {@code $richText(value, format, "inline")} narrows anything longer to one.
 */
@Slf4j
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
                + converter.defaultFormatId() + "'). $richText(value, format, \"inline\") narrows it to"
                + " the one paragraph an inline field takes";
    }

    /** The value in the default format. */
    public Map<String, Object> execute(ExpressionContext ctx, Object value) {
        return execute(ctx, value, null);
    }

    /**
     * The value in the named format; nothing for nothing, so an absent field stays absent.
     *
     * <p>One value, one document. A list of rich text is mapped item by item the JSONata way, inside
     * an array constructor — {@code [$doc.alineas.$richText($, "html")]} — because JSONata collapses
     * a one-item result into its item, and a list field refuses a single value.
     */
    public Map<String, Object> execute(ExpressionContext ctx, Object value, String format) {
        return execute(ctx, value, format, null);
    }

    /**
     * The same, as a document of the named kind: {@code "inline"} narrows it to the one paragraph an
     * inline field takes, {@code "block"} (or nothing) keeps paragraphs and lists.
     */
    public Map<String, Object> execute(ExpressionContext ctx, Object value, String format, String kind) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            return null;
        }
        RichTextLosses losses = new RichTextLosses();
        Map<String, Object> document = converter.toDocument(value, format, kindOf(kind), losses);
        if (ProseMirrorSanitizer.isBlank(document)) {
            // Markup around nothing is nothing, so a required field reports it missing.
            return null;
        }
        if (!losses.isEmpty()) {
            // Kinds and counts only, never the text. A warning when a letter is being generated —
            // that is when it costs something — and debug while previewing, which repeats per change.
            String formatId = format != null ? format : converter.defaultFormatId();
            if (ctx != null && ctx.getExecution() != null) {
                log.warn("$richText read a value as '{}' and lost what Epistola cannot render: {}", formatId, losses);
            } else {
                log.debug("$richText read a value as '{}' and lost what Epistola cannot render: {}", formatId, losses);
            }
        }
        return document;
    }

    private static RichTextKind kindOf(String kind) {
        if (kind == null || kind.isBlank()) {
            return RichTextKind.BLOCK;
        }
        try {
            return RichTextKind.valueOf(kind.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("$richText takes the kind 'inline' or 'block', not '" + kind + "'");
        }
    }
}
