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

import app.epistola.valtimo.domain.TemplateField;
import app.epistola.valtimo.domain.TemplateField.RichTextKind;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;

/**
 * Translates rich text between the format a case keeps it in and the ProseMirror document Epistola
 * accepts (ADR 0008).
 *
 * <p>Which format a value is in is decided explicitly, never guessed:
 * <ol>
 *   <li>an object shaped {@code {"type": "doc", …}} is already ProseMirror;</li>
 *   <li>otherwise a mapping that says so — {@code $richText(value, "markdown")} — has converted it
 *       already, by step 1;</li>
 *   <li>otherwise the configured default applies ({@code epistola.rich-text.default-format},
 *       {@code plain} unless an installation says differently).</li>
 * </ol>
 * Nothing tells HTML from markdown from plain text by looking at a string: {@code 2 * 3 * 4} is
 * arithmetic to one installation and emphasis to another.
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
        if (ProseMirrorSanitizer.isDocument(value)) {
            return ProseMirrorSanitizer.sanitize(value, kind, losses);
        }
        RichTextFormat format = formatId == null ? defaultFormat : formats.get(formatId);
        if (format == null) {
            throw new IllegalArgumentException("Unknown rich-text format '" + formatId
                    + "'. Known formats: " + formatIds());
        }
        return ProseMirrorSanitizer.sanitize(format.toProseMirror(value, losses), kind, losses);
    }

    /**
     * The template data with every rich-text field turned into a valid document for its kind,
     * read from the default format where it is not a document already. Other fields, and data
     * without rich-text fields, are returned untouched.
     *
     * <p>A field taking a list of rich text has each item converted, empty items left out; a single
     * value given to it is taken as a list of one.
     *
     * <p>A string with no text in it — blank, or markup around nothing, like the {@code <p><br></p>}
     * an emptied editor leaves — is removed rather than converted: it means "nothing here", and an
     * empty document would claim otherwise, letting a required field pass empty. A value that is neither text nor a document — a list, an
     * object of another shape — is left for Epistola to refuse with its own message.
     *
     * @throws RichTextConversionException when a format fails on a value
     */
    public Map<String, Object> normalize(Map<String, Object> data, List<TemplateField> fields) {
        return normalize(data, fields, (path, losses) -> { });
    }

    /**
     * The same, telling {@code report} for each field whose value lost something on the way — a
     * heading flattened, an image left out — by its path and what was lost, never the text.
     */
    public Map<String, Object> normalize(
            Map<String, Object> data,
            List<TemplateField> fields,
            BiConsumer<String, RichTextLosses> report
    ) {
        if (data == null || fields == null || fields.stream().noneMatch(RichTextConverter::holdsRichText)) {
            return data;
        }
        return normalizeObject(data, fields, report);
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

    /** Whether a field is rich text, or holds one somewhere below it. */
    static boolean holdsRichText(TemplateField field) {
        if (field.richTextKind() != null) {
            return true;
        }
        return field.children() != null && field.children().stream().anyMatch(RichTextConverter::holdsRichText);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeObject(
            Map<String, Object> data,
            List<TemplateField> fields,
            BiConsumer<String, RichTextLosses> report
    ) {
        Map<String, Object> out = new LinkedHashMap<>(data);
        for (TemplateField field : fields) {
            if (!holdsRichText(field) || !out.containsKey(field.name())) {
                continue;
            }
            Object value = out.get(field.name());
            RichTextKind kind = field.richTextKind();
            RichTextLosses losses = new RichTextLosses();
            if (kind != null && field.takesList()) {
                if (value != null) {
                    List<Object> converted = convertList(field, value, kind, losses);
                    if (converted == null) {
                        out.remove(field.name());
                    } else {
                        out.put(field.name(), converted);
                    }
                }
            } else if (kind != null) {
                if (value instanceof String text && text.isBlank()) {
                    out.remove(field.name());
                } else if (value != null) {
                    Object converted = convert(field, value, kind, losses);
                    if (value instanceof String && ProseMirrorSanitizer.isBlank(converted)) {
                        out.remove(field.name());
                    } else {
                        out.put(field.name(), converted);
                    }
                }
            } else if (field.fieldType() == TemplateField.FieldType.OBJECT && value instanceof Map<?, ?> nested) {
                out.put(field.name(), normalizeObject((Map<String, Object>) nested, field.children(), report));
            } else if (field.fieldType() == TemplateField.FieldType.ARRAY && value instanceof List<?> items) {
                List<Object> normalized = new ArrayList<>(items.size());
                for (Object item : items) {
                    normalized.add(item instanceof Map<?, ?> row
                            ? normalizeObject((Map<String, Object>) row, field.children(), report)
                            : item);
                }
                out.put(field.name(), normalized);
            }
            if (!losses.isEmpty()) {
                report.accept(field.path(), losses);
            }
        }
        return out;
    }

    /**
     * A list field's value as a list of documents, or null when no item holds anything. A single
     * value is a list of one: JSONata collapses a one-item list into its item, so a mapping over a
     * case list hands a lone value over whenever the case has just one.
     */
    private List<Object> convertList(TemplateField field, Object value, RichTextKind kind, RichTextLosses losses) {
        List<?> items = value instanceof List<?> list ? list : List.of(value);
        List<Object> converted = new ArrayList<>(items.size());
        for (Object item : items) {
            Object document = convertPresent(field, item, kind, losses);
            if (document != null) {
                converted.add(document);
            }
        }
        return converted.isEmpty() ? null : converted;
    }

    /** An item of a rich-text list, or null when it holds nothing and should be left out. */
    private Object convertPresent(TemplateField field, Object value, RichTextKind kind, RichTextLosses losses) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            return null;
        }
        Object converted = convert(field, value, kind, losses);
        return value instanceof String && ProseMirrorSanitizer.isBlank(converted) ? null : converted;
    }

    private Object convert(TemplateField field, Object value, RichTextKind kind, RichTextLosses losses) {
        if (ProseMirrorSanitizer.isDocument(value)) {
            return ProseMirrorSanitizer.sanitize(value, kind, losses);
        }
        if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
            return value;
        }
        try {
            return ProseMirrorSanitizer.sanitize(
                    defaultFormat.toProseMirror(String.valueOf(value), losses), kind, losses);
        } catch (RuntimeException e) {
            // Raised without the cause: a format's message may quote the value, which is the case's
            // own text (CLAUDE.md, "Never log personal data"). The class name still says what broke.
            throw new RichTextConversionException("Rich-text field '" + field.path()
                    + "' could not be read as '" + defaultFormat.id() + "' (" + e.getClass().getName() + ")");
        }
    }
}
