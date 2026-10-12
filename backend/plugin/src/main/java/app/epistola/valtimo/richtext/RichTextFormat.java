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

import java.util.Map;

/**
 * A format a case may keep rich text in, and how to turn it into the ProseMirror document Epistola
 * accepts — and, optionally, back.
 *
 * <p><b>This is an extension point.</b> The plugin ships {@code plain}, {@code html},
 * {@code markdown} and {@code prosemirror}; an installation that keeps text in a format of its own
 * declares one more Spring bean implementing this interface, and its {@link #id()} is immediately
 * usable as {@code $richText(value, "<id>")} and as {@code epistola.rich-text.default-format}. How
 * a person types the format is a separate concern, {@link RichTextEditor}. See ADR 0008 for why
 * conversion lives here and not in Epistola.
 *
 * <p>An implementation does not have to produce a <i>valid</i> document. Whatever it returns is
 * passed through {@link ProseMirrorSanitizer}, which drops what Epistola's schema does not allow and
 * shapes the result to the field's kind. What it should do is keep as much of the meaning as the
 * schema can carry, and record in {@code losses} what it reads but cannot keep.
 *
 * <p>The value it receives is the case's own text: personal data. An implementation must never log
 * it, or put it in an exception message; a failure is reported by the field and the format alone.
 */
public interface RichTextFormat {

    /** The id a mapping and the configuration name this format by, e.g. {@code markdown}. */
    String id();

    /**
     * Convert a value in this format to a ProseMirror document ({@code {"type": "doc", ...}}).
     *
     * @param value  the value as the case holds it; usually a string
     * @param losses where to record what the format reads but the document cannot hold — a
     *               heading, an image; kinds only, never content
     * @return a ProseMirror document, which the caller sanitizes
     */
    Map<String, Object> toProseMirror(Object value, RichTextLosses losses);

    /**
     * The reverse: a ProseMirror document as a value in this format, to show an existing document
     * in an editor for it (a retry form prefills with what was rendered).
     *
     * <p>Optional. A format that returns null cannot be edited from a document, and such a document
     * is offered in the HTML editor instead.
     *
     * @param doc a sanitized ProseMirror document
     * @return the document in this format, or null when the format cannot express documents back
     */
    default Object fromProseMirror(Map<String, Object> doc) {
        return null;
    }
}
