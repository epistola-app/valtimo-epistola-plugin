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
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rich text that says what it is (ADR 0008).
 *
 * <p>What a person types into one of the plugin's editors — the letter composer, the retry form —
 * is submitted as a <b>tagged value</b> rather than a bare string:
 * <pre>{@code {"$richText": {"format": "html", "kind": "BLOCK", "value": "<p>Het bezwaar is …</p>"}}}</pre>
 * The editor that wrote it states the format, so nothing has to work out later which format a
 * string is in, or where it came from. The tag travels with the value: through the composer's merge
 * with mapped data, onto a process variable, into a list.
 *
 * <p>{@link #toDocuments} turns every tagged value into the document Epistola takes, and is applied
 * to all data as it is sent. Data without tags — what a mapping produced, where the author converts
 * with {@code $richText(value, format)} — passes unchanged, and a bare string in a rich-text field
 * is refused by Epistola.
 */
@Slf4j
public class RichTextValues {

    /** The key a tagged value is wrapped in. */
    public static final String TAG = "$richText";

    /** Why the data is sent, which decides how loudly a loss is reported. */
    public enum Purpose {
        /** Repeated on every change in an editor: a loss is logged at debug. */
        PREVIEW,
        /** A letter: a loss is a warning, because the editor accepted more than the letter shows. */
        GENERATION
    }

    /** Stands for a value that holds nothing, so its key or list item is left out. */
    private static final Object NOTHING = new Object();

    private final RichTextConverter converter;

    public RichTextValues(RichTextConverter converter) {
        this.converter = converter;
    }

    /** The formats this plugin ships, for a client constructed without the application's beans. */
    public static RichTextValues withShippedFormats() {
        return new RichTextValues(new RichTextConverter(List.of(new PlainTextFormat(), new HtmlFormat(),
                new MarkdownFormat(), new ProseMirrorFormat(new ObjectMapper())), "html"));
    }

    /** Whether a value is a tagged rich-text value. */
    public static boolean isTagged(Object value) {
        return value instanceof Map<?, ?> map && map.size() == 1 && map.get(TAG) instanceof Map<?, ?>;
    }

    /**
     * The data with every tagged value as a document of its kind. A tagged value holding nothing —
     * blank, or markup around nothing like the {@code <p><br></p>} an emptied editor leaves — is
     * left out, so a required field is reported missing rather than passing empty. Everything else
     * is returned as it was.
     *
     * @param templateId named in a log of what a conversion lost; never the text
     * @throws RichTextConversionException when a tag names no known format, or its format fails on
     *                                     the value — naming the field, never the text
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> toDocuments(Map<String, Object> data, String templateId, Purpose purpose) {
        if (data == null) {
            return null;
        }
        Object converted = resolve(data, "", templateId, purpose);
        return converted == NOTHING ? Map.of() : (Map<String, Object>) converted;
    }

    /**
     * The data with every tagged value replaced by what was typed, in the editor's own format: what
     * a case keeps, and what an editor shows again.
     */
    public static Object unwrap(Object value) {
        if (isTagged(value)) {
            return ((Map<?, ?>) ((Map<?, ?>) value).get(TAG)).get("value");
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, item) -> out.put(String.valueOf(key), unwrap(item)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            list.forEach(item -> out.add(unwrap(item)));
            return out;
        }
        return value;
    }

    /** {@link #unwrap(Object)} for a map, keeping its type. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> unwrapAll(Map<String, Object> data) {
        return data == null ? null : (Map<String, Object>) unwrap(data);
    }

    private Object resolve(Object value, String path, String templateId, Purpose purpose) {
        if (isTagged(value)) {
            return convert((Map<?, ?>) ((Map<?, ?>) value).get(TAG), path, templateId, purpose);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                Object resolved = resolve(item, path.isEmpty() ? name : path + "." + name, templateId, purpose);
                if (resolved != NOTHING) {
                    out.put(name, resolved);
                }
            });
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            boolean anyTagged = false;
            for (int i = 0; i < list.size(); i++) {
                anyTagged |= isTagged(list.get(i));
                Object resolved = resolve(list.get(i), path + "[" + i + "]", templateId, purpose);
                if (resolved != NOTHING) {
                    out.add(resolved);
                }
            }
            // A list of rich text whose every item was empty holds nothing, like a single empty value.
            return anyTagged && out.isEmpty() ? NOTHING : out;
        }
        return value;
    }

    private Object convert(Map<?, ?> tag, String path, String templateId, Purpose purpose) {
        Object value = tag.get("value");
        if (value == null || (value instanceof String text && text.isBlank())) {
            return NOTHING;
        }
        String subject = "Rich-text field '" + path + "'";
        if (!(tag.get("format") instanceof String format) || format.isBlank()) {
            throw new RichTextConversionException(subject + " does not say which format it is in");
        }
        RichTextLosses losses = new RichTextLosses();
        Map<String, Object> document;
        try {
            document = converter.toDocument(value, format, kindOf(tag.get("kind"), subject), losses, subject);
        } catch (IllegalArgumentException e) {
            // Unknown format: the message names the format and the known ones, never the value.
            throw new RichTextConversionException(subject + ": " + e.getMessage());
        }
        if (ProseMirrorSanitizer.isBlank(document)) {
            return NOTHING;
        }
        if (!losses.isEmpty()) {
            // Field paths and kinds of content, never the text.
            if (purpose == Purpose.GENERATION) {
                log.warn("Rich text for field '{}' of template '{}' lost what Epistola cannot render: {}",
                        path, templateId, losses);
            } else {
                log.debug("Rich text for field '{}' of template '{}' lost what Epistola cannot render: {}",
                        path, templateId, losses);
            }
        }
        return document;
    }

    /** The tag's kind; a block when it names none, which Epistola still checks against the field. */
    private static RichTextKind kindOf(Object kind, String subject) {
        if (kind == null) {
            return RichTextKind.BLOCK;
        }
        try {
            return RichTextKind.valueOf(String.valueOf(kind).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RichTextConversionException(subject + " names kind '" + kind
                    + "', which is neither INLINE nor BLOCK");
        }
    }
}
