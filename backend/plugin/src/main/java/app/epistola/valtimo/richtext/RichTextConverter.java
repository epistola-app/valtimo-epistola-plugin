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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Translates rich text between the formats a case keeps it in and the ProseMirror document Epistola
 * accepts (ADR 0008).
 *
 * <p>Which format a value is in is always stated, never guessed: an editor tags what was typed
 * with its format ({@link RichTextValues}), and a mapping names it, {@code $richText(value,
 * "markdown")}. Nothing tells HTML from markdown from plain text by looking at a string:
 * {@code 2 * 3 * 4} is arithmetic to one installation and emphasis to another. The configured
 * default ({@code epistola.rich-text.default-format}) only decides which editor the plugin's forms
 * offer, and what {@code $richText(value)} reads without a format.
 */
public class RichTextConverter {

    private final Map<String, RichTextFormat> formats;
    private final RichTextFormat defaultFormat;

    /**
     * @param formats         every format available, the shipped ones and an installation's own
     * @param defaultFormatId the format a string in a rich-text field is read as
     * @throws IllegalStateException when two formats share an id, or the default names none of them
     */
    public RichTextConverter(Collection<? extends RichTextFormat> formats, String defaultFormatId) {
        Map<String, RichTextFormat> byId = new TreeMap<>();
        for (RichTextFormat format : formats) {
            RichTextFormat previous = byId.put(format.id(), format);
            if (previous != null) {
                throw new IllegalStateException("Two rich-text formats share the id '" + format.id() + "': "
                        + previous.getClass().getName() + " and " + format.getClass().getName());
            }
        }
        this.formats = Map.copyOf(byId);
        this.defaultFormat = byId.get(defaultFormatId);
        if (defaultFormat == null) {
            throw new IllegalStateException("epistola.rich-text.default-format is '" + defaultFormatId
                    + "', which is not a rich-text format. Known formats: " + byId.keySet());
        }
    }

    public Set<String> formatIds() {
        return new java.util.TreeSet<>(formats.keySet());
    }

    public String defaultFormatId() {
        return defaultFormat.id();
    }

    /**
     * A value as a document of the given kind.
     *
     * @param value    a document, or a value in {@code formatId}
     * @param formatId the format a non-document value is in; null for the default
     * @throws IllegalArgumentException when no format has that id
     */
    public Map<String, Object> toDocument(Object value, String formatId, RichTextKind kind) {
        return toDocument(value, formatId, kind, new RichTextLosses());
    }

    /** The same, recording in {@code losses} what the document could not carry. */
    public Map<String, Object> toDocument(Object value, String formatId, RichTextKind kind, RichTextLosses losses) {
        return toDocument(value, formatId, kind, losses, "A value");
    }

    /** The same, naming {@code subject} — a field's path, say — when the format fails. */
    Map<String, Object> toDocument(
            Object value, String formatId, RichTextKind kind, RichTextLosses losses, String subject) {
        if (ProseMirrorSanitizer.isDocument(value)) {
            return ProseMirrorSanitizer.sanitize(value, kind, losses);
        }
        RichTextFormat format = formatId == null ? defaultFormat : formats.get(formatId);
        if (format == null) {
            throw new IllegalArgumentException("Unknown rich-text format '" + formatId
                    + "'. Known formats: " + formatIds());
        }
        return read(value, format, kind, losses, subject);
    }

    /**
     * A value read in a format and narrowed to the schema. A format's failure is raised again
     * <b>without its cause</b>: the cause's message may quote the value, which is the case's own
     * text, and a cause travels into every log that prints the stack (CLAUDE.md, "Never log
     * personal data"). Its class name still says what broke; {@code subject} says where.
     */
    private static Map<String, Object> read(
            Object value, RichTextFormat format, RichTextKind kind, RichTextLosses losses, String subject) {
        try {
            return ProseMirrorSanitizer.sanitize(format.toProseMirror(value, losses), kind, losses);
        } catch (RuntimeException e) {
            throw new RichTextConversionException(subject + " could not be read as '" + format.id()
                    + "' (" + e.getClass().getName() + ")");
        }
    }

    /**
     * A value as an editor for the default format shows it. A document is written back out in that
     * format, or dropped when the format cannot express one; anything else is already in it.
     */
    public Object toEditorValue(Object value, RichTextKind kind) {
        if (value instanceof List<?> items) {
            List<Object> shown = new ArrayList<>(items.size());
            for (Object item : items) {
                shown.add(toEditorValue(item, kind));
            }
            return shown;
        }
        if (!ProseMirrorSanitizer.isDocument(value)) {
            return value;
        }
        return defaultFormat.fromProseMirror(ProseMirrorSanitizer.sanitize(value, kind));
    }
}
