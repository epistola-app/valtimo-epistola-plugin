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

import java.util.List;
import java.util.Map;

/** Small builders for expected documents, shared by the format tests. */
final class Docs {

    static final Map<String, Object> BREAK = Map.of("type", "hard_break");
    static final Map<String, Object> STRONG = Map.of("type", "strong");
    static final Map<String, Object> EM = Map.of("type", "em");
    static final Map<String, Object> UNDERLINE = Map.of("type", "underline");
    static final Map<String, Object> STRIKE = Map.of("type", "strikethrough");
    static final Map<String, Object> SUB = Map.of("type", "subscript");
    static final Map<String, Object> SUP = Map.of("type", "superscript");

    private Docs() {
    }

    static Map<String, Object> doc(Object... blocks) {
        return Map.of("type", "doc", "content", List.of(blocks));
    }

    static Map<String, Object> p(Object... inline) {
        return inline.length == 0
                ? Map.of("type", "paragraph")
                : Map.of("type", "paragraph", "content", List.of(inline));
    }

    static Map<String, Object> text(String text, Object... marks) {
        return marks.length == 0
                ? Map.of("type", "text", "text", text)
                : Map.of("type", "text", "text", text, "marks", List.of(marks));
    }

    static Map<String, Object> link(String href) {
        return Map.of("type", "link", "attrs", Map.of("href", href));
    }

    static Map<String, Object> color(String hex) {
        return Map.of("type", "textStyle", "attrs", Map.of("color", hex));
    }

    static Map<String, Object> ul(Object... items) {
        return Map.of("type", "bullet_list", "content", List.of(items));
    }

    static Map<String, Object> ol(Object... items) {
        return Map.of("type", "ordered_list", "content", List.of(items));
    }

    static Map<String, Object> olFrom(int order, Object... items) {
        return Map.of("type", "ordered_list", "attrs", Map.of("order", order), "content", List.of(items));
    }

    static Map<String, Object> li(Object... blocks) {
        return Map.of("type", "list_item", "content", List.of(blocks));
    }
}
