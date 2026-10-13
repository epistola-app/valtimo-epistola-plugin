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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Epistola's own format, for a case that stores exactly what Epistola renders. Accepts the
 * document as an object or as its JSON text; either way it is still sanitized, so a document from
 * a newer or older editor with nodes this schema version lacks is narrowed rather than refused.
 */
public class ProseMirrorFormat implements RichTextFormat {

    public static final String ID = "prosemirror";

    private final ObjectMapper objectMapper;

    public ProseMirrorFormat(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> toProseMirror(Object value, RichTextLosses losses) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return objectMapper.readValue(text, new TypeReference<Map<String, Object>>() { });
            } catch (Exception e) {
                // Never the text itself, nor the parser's message: both quote the case's content.
                throw new IllegalArgumentException("The value is not a ProseMirror document in JSON");
            }
        }
        return Map.of("type", "doc");
    }

    /** As JSON text: an editor shows text, and an object would read as "[object Object]". */
    @Override
    public Object fromProseMirror(Map<String, Object> doc) {
        try {
            return objectMapper.writeValueAsString(doc);
        } catch (Exception e) {
            return null;
        }
    }
}
