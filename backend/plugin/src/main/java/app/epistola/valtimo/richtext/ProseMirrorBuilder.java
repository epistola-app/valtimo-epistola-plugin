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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static app.epistola.valtimo.richtext.Nodes.contentOf;
import static app.epistola.valtimo.richtext.Nodes.node;

/**
 * Builds a ProseMirror document from a stream of events — text, breaks, block boundaries, lists —
 * which is the shape both a markup parser and a line-based one naturally produce.
 *
 * <p>It is forgiving rather than strict: text inside a list but outside an item gets an item, a
 * list directly inside a list joins the item before it, and an item opened before the previous one
 * was closed closes it. Whitespace is treated the way a browser renders it: runs are expected to be
 * collapsed by the caller, and the builder drops what would be invisible — leading space on a line,
 * trailing space and breaks at the end of a paragraph, paragraphs left empty.
 *
 * <p>The result still goes through {@link ProseMirrorSanitizer}; this only has to get the structure
 * right, not every rule of the schema.
 */
final class ProseMirrorBuilder {

    private enum Kind { DOC, LIST, ITEM }

    private record Frame(Kind kind, Map<String, Object> node, List<Map<String, Object>> content) {
    }

    private final Map<String, Object> doc = node("doc");
    private final Deque<Frame> stack = new ArrayDeque<>();
    private Map<String, Object> paragraph;
    private List<Map<String, Object>> paragraphContent;
    private List<Map<String, Object>> paragraphParent;

    ProseMirrorBuilder() {
        stack.push(new Frame(Kind.DOC, doc, contentOf(doc)));
    }

    /** Append text with these marks to the current paragraph, opening one when needed. */
    void text(String text, List<Map<String, Object>> marks) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (paragraph == null) {
            if (text.isBlank()) {
                return;
            }
            startParagraph();
        }
        String value = text;
        if (atLineStart() || endsWithSpace()) {
            value = stripLeadingSpaces(value);
        }
        if (value.isEmpty()) {
            return;
        }
        Map<String, Object> node = node("text");
        node.put("text", value);
        if (marks != null && !marks.isEmpty()) {
            node.put("marks", new ArrayList<>(marks));
        }
        paragraphContent.add(node);
    }

    void hardBreak() {
        if (paragraph == null) {
            startParagraph();
        }
        trimTrailingSpace();
        paragraphContent.add(node("hard_break"));
    }

    /** End the current paragraph, if any; the next text starts a new one. */
    void endBlock() {
        if (paragraph == null) {
            return;
        }
        boolean changed = true;
        while (changed && !paragraphContent.isEmpty()) {
            changed = false;
            Map<String, Object> last = paragraphContent.get(paragraphContent.size() - 1);
            if ("hard_break".equals(last.get("type"))) {
                paragraphContent.remove(paragraphContent.size() - 1);
                changed = true;
            } else if (trimTrailingSpace()) {
                changed = true;
            }
        }
        if (paragraphContent.isEmpty()) {
            // By identity: an equal-looking node elsewhere in the parent is not this one.
            for (int i = paragraphParent.size() - 1; i >= 0; i--) {
                if (paragraphParent.get(i) == paragraph) {
                    paragraphParent.remove(i);
                    break;
                }
            }
        }
        paragraph = null;
        paragraphContent = null;
        paragraphParent = null;
    }

    void openList(boolean ordered, Integer start) {
        endBlock();
        if (current().kind() == Kind.LIST) {
            reopenLastItemOrOpenOne();
        }
        Map<String, Object> list = node(ordered ? "ordered_list" : "bullet_list");
        if (ordered && start != null && start > 1) {
            list.put("attrs", new LinkedHashMap<>(Map.of("order", start)));
        }
        current().content().add(list);
        stack.push(new Frame(Kind.LIST, list, contentOf(list)));
    }

    void closeList() {
        endBlock();
        while (stack.size() > 1) {
            if (stack.pop().kind() == Kind.LIST) {
                return;
            }
        }
    }

    void openItem() {
        endBlock();
        if (current().kind() == Kind.ITEM) {
            stack.pop();
        }
        if (current().kind() != Kind.LIST) {
            openList(false, null);
        }
        Map<String, Object> item = node("list_item");
        current().content().add(item);
        stack.push(new Frame(Kind.ITEM, item, contentOf(item)));
    }

    void closeItem() {
        endBlock();
        if (current().kind() == Kind.ITEM) {
            stack.pop();
        }
    }

    /** How many lists are open around the current position. */
    int listDepth() {
        return (int) stack.stream().filter(frame -> frame.kind() == Kind.LIST).count();
    }

    Map<String, Object> build() {
        endBlock();
        return doc;
    }

    private void startParagraph() {
        if (current().kind() == Kind.LIST) {
            openItem();
        }
        paragraph = node("paragraph");
        paragraphContent = contentOf(paragraph);
        paragraphParent = current().content();
        paragraphParent.add(paragraph);
    }

    /** A list written straight into a list belongs to the item before it, as its indentation meant. */
    private void reopenLastItemOrOpenOne() {
        List<Map<String, Object>> items = current().content();
        if (!items.isEmpty() && "list_item".equals(items.get(items.size() - 1).get("type"))) {
            Map<String, Object> last = items.get(items.size() - 1);
            stack.push(new Frame(Kind.ITEM, last, contentOf(last)));
        } else {
            openItem();
        }
    }

    private Frame current() {
        return stack.peek();
    }

    private boolean atLineStart() {
        return paragraphContent.isEmpty()
                || "hard_break".equals(paragraphContent.get(paragraphContent.size() - 1).get("type"));
    }

    private boolean endsWithSpace() {
        if (paragraphContent.isEmpty()) {
            return false;
        }
        Map<String, Object> last = paragraphContent.get(paragraphContent.size() - 1);
        return last.get("text") instanceof String text && text.endsWith(" ");
    }

    /** Trim trailing spaces off the last text node, dropping it if nothing is left. */
    private boolean trimTrailingSpace() {
        if (paragraphContent.isEmpty()) {
            return false;
        }
        Map<String, Object> last = paragraphContent.get(paragraphContent.size() - 1);
        if (!(last.get("text") instanceof String text) || !text.endsWith(" ")) {
            return false;
        }
        String trimmed = text.replaceAll(" +$", "");
        if (trimmed.isEmpty()) {
            paragraphContent.remove(paragraphContent.size() - 1);
        } else {
            last.put("text", trimmed);
        }
        return true;
    }

    private static String stripLeadingSpaces(String text) {
        int start = 0;
        while (start < text.length() && text.charAt(start) == ' ') {
            start++;
        }
        return text.substring(start);
    }



}
