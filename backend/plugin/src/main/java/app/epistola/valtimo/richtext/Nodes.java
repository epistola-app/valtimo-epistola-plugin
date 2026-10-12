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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Building and reading ProseMirror nodes, which this package represents as plain maps — the shape
 * they have as JSON, and the shape template data has everywhere else in the plugin.
 */
final class Nodes {

    private Nodes() {
    }

    /** A node, or a mark, of this type and nothing else yet. */
    static Map<String, Object> node(String type) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", type);
        return node;
    }

    static Map<String, Object> text(String text, List<Map<String, Object>> marks) {
        Map<String, Object> node = node("text");
        node.put("text", text);
        if (marks != null && !marks.isEmpty()) {
            node.put("marks", new ArrayList<>(marks));
        }
        return node;
    }

    static Map<String, Object> hardBreak() {
        return node("hard_break");
    }

    /** A paragraph holding these inline nodes; {@code attrs} only when given. */
    static Map<String, Object> paragraph(List<Map<String, Object>> inline, Map<String, Object> attrs) {
        Map<String, Object> paragraph = node("paragraph");
        if (attrs != null) {
            paragraph.put("attrs", attrs);
        }
        if (!inline.isEmpty()) {
            paragraph.put("content", new ArrayList<>(inline));
        }
        return paragraph;
    }

    /** A node's type, or "" when it has none — for input that may be anything. */
    static String typeOf(Map<?, ?> node) {
        return node.get("type") instanceof String type ? type : "";
    }

    /** A node's children as they are, for reading input that may hold anything. */
    static List<?> children(Map<?, ?> node) {
        return node.get("content") instanceof List<?> list ? list : List.of();
    }

    /** A built node's children, for reading a document this package produced. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> childNodes(Map<String, Object> node) {
        return node.get("content") instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    /** A built node's marks. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> marksOf(Map<String, Object> node) {
        return node.get("marks") instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    /** A node's children, to add to: created when the node has none yet. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> contentOf(Map<String, Object> node) {
        return (List<Map<String, Object>>) node.computeIfAbsent("content", key -> new ArrayList<>());
    }
}
