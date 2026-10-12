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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shapes any ProseMirror-like document into one Epistola accepts for a field of a given kind.
 *
 * <p>This is where "always valid towards Epistola" (ADR 0008) is enforced, once, for every format:
 * a converter only has to say what the text <i>means</i>, and whatever it produces passes through
 * here. The rule for anything outside {@code richtext-*-v1.json} is to keep its text and lose its
 * structure — a heading becomes a paragraph, a table's cells become paragraphs, an unknown mark is
 * dropped — never to pass it through and have Epistola refuse the letter. What it narrows is
 * recorded in a {@link RichTextLosses}, by kind, so it can be reported.
 *
 * <p>Output uses the node and mark names Epistola's own editor writes ({@code hard_break},
 * {@code bullet_list}, {@code strikethrough}, …); the aliases the schema also accepts are read but
 * not written.
 */
public final class ProseMirrorSanitizer {

    private static final int MAX_DEPTH = 64;
    private static final Set<String> LINK_SCHEMES = Set.of("http", "https", "mailto", "tel");
    private static final Pattern HEX_COLOR = Pattern.compile("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$");
    private static final Set<String> LIST_STYLES = Set.of("disc", "circle", "square", "dash");
    private static final Set<String> LIST_TYPES =
            Set.of("decimal", "lower-alpha", "upper-alpha", "lower-roman", "upper-roman");
    /**
     * The order Epistola's editor keeps marks in: prosemirror-schema-basic's (link, em, strong),
     * then its own, with the data-only {@code textStyle} last. ProseMirror sorts a node's marks by
     * schema rank, so writing them in that order makes the same formatting the same document,
     * however the source happened to nest it ({@code <b><i>} or {@code <i><b>}).
     */
    private static final List<String> MARK_ORDER = List.of(
            "link", "em", "strong", "underline", "strikethrough", "subscript", "superscript", "textStyle");

    private ProseMirrorSanitizer() {
    }

    /** Whether a value is shaped like a ProseMirror document, i.e. was not meant as text. */
    public static boolean isDocument(Object value) {
        return value instanceof Map<?, ?> map && "doc".equals(map.get("type"));
    }

    /** Whether a document holds no text at all — only empty paragraphs, breaks or nothing. */
    public static boolean isBlank(Object node) {
        if (!(node instanceof Map<?, ?> map)) {
            return true;
        }
        if (map.get("text") instanceof String text && !text.isBlank()) {
            return false;
        }
        return content(map).stream().allMatch(ProseMirrorSanitizer::isBlank);
    }

    /**
     * A document valid against the schema for {@code kind}.
     *
     * @param document a ProseMirror document, or a single node; anything else yields an empty one
     */
    public static Map<String, Object> sanitize(Object document, RichTextKind kind) {
        return sanitize(document, kind, new RichTextLosses());
    }

    /**
     * The same, recording in {@code losses} what had to go: structure flattened into paragraphs,
     * nodes and marks the schema lacks, links and colours it cannot take.
     */
    public static Map<String, Object> sanitize(Object document, RichTextKind kind, RichTextLosses losses) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        if (document instanceof Map<?, ?> node) {
            new Walk(losses).collectBlocks(node, blocks, 0);
        }
        if (kind == RichTextKind.INLINE && (blocks.size() > 1
                || blocks.stream().anyMatch(block -> !"paragraph".equals(block.get("type"))))) {
            losses.lost("paragraphs or lists in an inline field");
        }
        return kind == RichTextKind.INLINE ? inlineDocument(blocks) : blockDocument(blocks);
    }

    /**
     * Only links that mean something in a printed letter, and none that run anything: a
     * {@code javascript:} or {@code data:} URL is dropped, and so is a relative one, which has no
     * page to be relative to.
     */
    static boolean isAllowedHref(String href) {
        String trimmed = href.trim();
        int colon = trimmed.indexOf(':');
        if (colon <= 0) {
            return false;
        }
        String scheme = trimmed.substring(0, colon).toLowerCase(Locale.ROOT);
        return LINK_SCHEMES.contains(scheme) && trimmed.length() > colon + 1;
    }

    /** One walk over a document, recording what it narrows. */
    private static final class Walk {

        private final RichTextLosses losses;

        Walk(RichTextLosses losses) {
            this.losses = losses;
        }

        void collectBlocks(Map<?, ?> node, List<Map<String, Object>> out, int depth) {
            if (depth > MAX_DEPTH) {
                return;
            }
            String type = typeOf(node);
            switch (type) {
                case "paragraph" -> out.add(paragraph(inline(content(node), depth), paragraphAttrs(node)));
                // Not in the schema, but their text is: kept as a paragraph rather than lost.
                case "heading" -> {
                    losses.lost("heading");
                    out.add(paragraph(inline(content(node), depth), null));
                }
                case "code_block", "codeBlock" -> {
                    losses.lost("code block");
                    out.add(paragraph(inline(content(node), depth), null));
                }
                case "bullet_list", "bulletList", "ordered_list", "orderedList" -> {
                    Map<String, Object> list = list(node, depth);
                    if (list != null) {
                        out.add(list);
                    }
                }
                // An item outside a list: give it the list it is missing.
                case "list_item", "listItem" -> {
                    Map<String, Object> item = listItem(node, depth);
                    if (item != null) {
                        out.add(bulletList(List.of(item)));
                    }
                }
                case "text", "hard_break", "hardBreak" -> {
                    List<Map<String, Object>> inline = inline(List.of(node), depth);
                    if (!inline.isEmpty()) {
                        out.add(paragraph(inline, null));
                    }
                }
                default -> {
                    // doc, blockquote, a table, anything else holding blocks: keep what it holds.
                    recordUnsupported(type);
                    List<?> children = content(node);
                    if (!children.isEmpty()) {
                        for (Object child : children) {
                            if (child instanceof Map<?, ?> childNode) {
                                collectBlocks(childNode, out, depth + 1);
                            }
                        }
                    } else if (node.get("text") instanceof String text && !text.isEmpty()) {
                        out.add(paragraph(List.of(text(text, List.of())), null));
                    }
                }
            }
        }

        /**
         * Name a node the schema lacks, once per thing a person would recognise: a table, not each
         * of its rows and cells.
         */
        private void recordUnsupported(String type) {
            if (type.equals("doc") || type.isEmpty()
                    || (type.startsWith("table") && !type.equals("table"))) {
                return;
            }
            losses.lost(switch (type) {
                case "blockquote" -> "block quote";
                case "horizontal_rule", "horizontalRule" -> "horizontal rule";
                default -> type;
            });
        }

        private Map<String, Object> list(Map<?, ?> node, int depth) {
            boolean ordered = typeOf(node).startsWith("ordered");
            List<Map<String, Object>> items = new ArrayList<>();
            for (Object child : content(node)) {
                if (!(child instanceof Map<?, ?> childNode)) {
                    continue;
                }
                String childType = typeOf(childNode);
                if (childType.equals("list_item") || childType.equals("listItem")) {
                    Map<String, Object> item = listItem(childNode, depth + 1);
                    if (item != null) {
                        items.add(item);
                    }
                } else {
                    // A list directly inside a list, or a paragraph where an item belongs: in
                    // ProseMirror only an item may hold either, so give it one — or, for a nested
                    // list, add it to the item before it, which is what the indentation meant.
                    List<Map<String, Object>> blocks = new ArrayList<>();
                    collectBlocks(childNode, blocks, depth + 1);
                    if (blocks.isEmpty()) {
                        continue;
                    }
                    boolean nestedList = !"paragraph".equals(blocks.get(0).get("type"));
                    if (nestedList && !items.isEmpty()) {
                        contentOf(items.get(items.size() - 1)).addAll(blocks);
                    } else {
                        items.add(itemOf(blocks));
                    }
                }
            }
            if (items.isEmpty()) {
                return null;
            }
            Map<String, Object> list = new LinkedHashMap<>();
            list.put("type", ordered ? "ordered_list" : "bullet_list");
            Map<String, Object> attrs = ordered ? orderedListAttrs(node) : bulletListAttrs(node);
            if (!attrs.isEmpty()) {
                list.put("attrs", attrs);
            }
            list.put("content", items);
            return list;
        }

        private Map<String, Object> listItem(Map<?, ?> node, int depth) {
            List<Map<String, Object>> blocks = new ArrayList<>();
            for (Object child : content(node)) {
                if (child instanceof Map<?, ?> childNode) {
                    collectBlocks(childNode, blocks, depth + 1);
                }
            }
            return blocks.isEmpty() ? null : itemOf(blocks);
        }

        private Map<String, Object> bulletListAttrs(Map<?, ?> node) {
            Map<String, Object> attrs = new LinkedHashMap<>();
            if (node.get("attrs") instanceof Map<?, ?> source && source.get("listStyle") != null) {
                if (source.get("listStyle") instanceof String style && LIST_STYLES.contains(style)) {
                    attrs.put("listStyle", style);
                } else {
                    losses.lost("list style");
                }
            }
            return attrs;
        }

        private Map<String, Object> orderedListAttrs(Map<?, ?> node) {
            Map<String, Object> attrs = new LinkedHashMap<>();
            if (!(node.get("attrs") instanceof Map<?, ?> source)) {
                return attrs;
            }
            if (source.get("listType") != null) {
                if (source.get("listType") instanceof String listType && LIST_TYPES.contains(listType)) {
                    attrs.put("listType", listType);
                } else {
                    losses.lost("list style");
                }
            }
            if (source.get("order") instanceof Number order
                    && order.doubleValue() == Math.floor(order.doubleValue())
                    && order.longValue() >= 1 && order.longValue() <= Integer.MAX_VALUE) {
                attrs.put("order", order.intValue());
            }
            return attrs;
        }

        private List<Map<String, Object>> inline(List<?> nodes, int depth) {
            List<Map<String, Object>> out = new ArrayList<>();
            collectInline(nodes, out, depth);
            return out;
        }

        private void collectInline(List<?> nodes, List<Map<String, Object>> out, int depth) {
            if (depth > MAX_DEPTH) {
                return;
            }
            for (Object raw : nodes) {
                if (!(raw instanceof Map<?, ?> node)) {
                    continue;
                }
                switch (typeOf(node)) {
                    case "text" -> {
                        if (node.get("text") instanceof String text && !text.isEmpty()) {
                            appendText(out, text, marks(node.get("marks")));
                        }
                    }
                    case "hard_break", "hardBreak" -> out.add(hardBreak());
                    default -> {
                        recordUnsupported(typeOf(node));
                        List<?> children = content(node);
                        if (!children.isEmpty()) {
                            collectInline(children, out, depth + 1);
                        } else if (node.get("text") instanceof String text && !text.isEmpty()) {
                            appendText(out, text, List.of());
                        }
                        // Any other leaf — an image, an expression — has no text to keep.
                    }
                }
            }
        }

        private List<Map<String, Object>> marks(Object raw) {
            if (!(raw instanceof List<?> list)) {
                return List.of();
            }
            List<Map<String, Object>> marks = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Object entry : list) {
                if (!(entry instanceof Map<?, ?> mark)) {
                    continue;
                }
                Map<String, Object> sanitized = mark(mark);
                if (sanitized == null) {
                    continue;
                }
                String type = (String) sanitized.get("type");
                // Subscript and superscript exclude each other in Epistola's editor; the first wins.
                boolean excluded = (type.equals("subscript") && seen.contains("superscript"))
                        || (type.equals("superscript") && seen.contains("subscript"));
                if (seen.add(type) && !excluded) {
                    marks.add(sanitized);
                }
            }
            marks.sort(java.util.Comparator.comparingInt(mark -> MARK_ORDER.indexOf(mark.get("type"))));
            return marks;
        }

        private Map<String, Object> mark(Map<?, ?> mark) {
            String type = typeOf(mark);
            String canonical = switch (type) {
                case "strong", "bold" -> "strong";
                case "em", "italic" -> "em";
                case "underline" -> "underline";
                case "strike", "strikethrough" -> "strikethrough";
                case "subscript" -> "subscript";
                case "superscript" -> "superscript";
                case "link" -> "link";
                case "textStyle" -> "textStyle";
                default -> null;
            };
            if (canonical == null) {
                losses.lost(type.isEmpty() ? "formatting" : type);
                return null;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("type", canonical);
            Map<?, ?> attrs = mark.get("attrs") instanceof Map<?, ?> map ? map : Map.of();
            if (canonical.equals("link")) {
                if (!(attrs.get("href") instanceof String href) || !isAllowedHref(href)) {
                    losses.lost("link");
                    return null;
                }
                Map<String, Object> linkAttrs = new LinkedHashMap<>();
                linkAttrs.put("href", href.trim());
                for (String key : List.of("target", "rel")) {
                    if (attrs.get(key) instanceof String value) {
                        linkAttrs.put(key, value);
                    }
                }
                if (attrs.get("title") instanceof String title) {
                    linkAttrs.put("title", title);
                }
                out.put("attrs", linkAttrs);
            } else if (canonical.equals("textStyle")) {
                if (!(attrs.get("color") instanceof String color) || !HEX_COLOR.matcher(color).matches()) {
                    losses.lost("colour");
                    return null;
                }
                out.put("attrs", new LinkedHashMap<>(Map.of("color", color)));
            }
            return out;
        }
    }

    // --- Shared building blocks ---------------------------------------------------------------

    /** An item's content must start with a paragraph; a bare nested list gets an empty one. */
    private static Map<String, Object> itemOf(List<Map<String, Object>> blocks) {
        List<Map<String, Object>> content = new ArrayList<>(blocks);
        if (!"paragraph".equals(content.get(0).get("type"))) {
            content.add(0, paragraph(List.of(), null));
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "list_item");
        item.put("content", content);
        return item;
    }

    private static Map<String, Object> bulletList(List<Map<String, Object>> items) {
        Map<String, Object> list = new LinkedHashMap<>();
        list.put("type", "bullet_list");
        list.put("content", new ArrayList<>(items));
        return list;
    }

    /** The schema leaves a paragraph's attrs open; an object is all it asks. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> paragraphAttrs(Map<?, ?> node) {
        return node.get("attrs") instanceof Map<?, ?> attrs && !attrs.isEmpty()
                ? new LinkedHashMap<>((Map<String, Object>) attrs)
                : null;
    }

    private static Map<String, Object> paragraph(List<Map<String, Object>> inline, Map<String, Object> attrs) {
        Map<String, Object> paragraph = new LinkedHashMap<>();
        paragraph.put("type", "paragraph");
        if (attrs != null) {
            paragraph.put("attrs", attrs);
        }
        if (!inline.isEmpty()) {
            paragraph.put("content", new ArrayList<>(inline));
        }
        return paragraph;
    }

    /** Adjacent text with the same marks is one node, as ProseMirror itself would store it. */
    private static void appendText(List<Map<String, Object>> out, String text, List<Map<String, Object>> marks) {
        if (!out.isEmpty()) {
            Map<String, Object> last = out.get(out.size() - 1);
            if ("text".equals(last.get("type"))
                    && Objects.equals(last.getOrDefault("marks", List.of()), marks)) {
                last.put("text", last.get("text") + text);
                return;
            }
        }
        out.add(text(text, marks));
    }

    private static Map<String, Object> text(String text, List<Map<String, Object>> marks) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", "text");
        node.put("text", text);
        if (!marks.isEmpty()) {
            node.put("marks", marks);
        }
        return node;
    }

    private static Map<String, Object> hardBreak() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", "hard_break");
        return node;
    }

    // --- Documents ----------------------------------------------------------------------------

    private static Map<String, Object> blockDocument(List<Map<String, Object>> blocks) {
        List<Map<String, Object>> content = new ArrayList<>(blocks);
        if (content.isEmpty()) {
            // Epistola's editor requires at least one block; the schema allows none. Both accept one.
            content.add(paragraph(List.of(), null));
        }
        return document(content);
    }

    /**
     * Exactly one paragraph. Blocks become lines of it, and a list item keeps a visible marker
     * ("• ", "1. ") so the reader still sees a list where there can no longer be one.
     */
    private static Map<String, Object> inlineDocument(List<Map<String, Object>> blocks) {
        List<Map<String, Object>> inline = new ArrayList<>();
        flatten(blocks, inline);
        List<Map<String, Object>> merged = new ArrayList<>();
        for (Map<String, Object> node : inline) {
            if ("text".equals(node.get("type"))) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> marks = (List<Map<String, Object>>) node.getOrDefault("marks", List.of());
                appendText(merged, (String) node.get("text"), marks);
            } else {
                merged.add(node);
            }
        }
        return document(List.of(paragraph(merged, null)));
    }

    private static void flatten(List<Map<String, Object>> blocks, List<Map<String, Object>> out) {
        for (Map<String, Object> block : blocks) {
            String type = (String) block.get("type");
            if (type.equals("paragraph")) {
                List<Map<String, Object>> content = contentOf(block);
                if (content.isEmpty()) {
                    continue;
                }
                lineBreak(out);
                content.forEach(node -> out.add(new LinkedHashMap<>(node)));
            } else {
                boolean ordered = type.equals("ordered_list");
                int number = ordered && block.get("attrs") instanceof Map<?, ?> attrs
                        && attrs.get("order") instanceof Integer order ? order : 1;
                for (Map<String, Object> item : contentOf(block)) {
                    List<Map<String, Object>> itemBlocks = contentOf(item);
                    lineBreak(out);
                    out.add(text(ordered ? (number++) + ". " : "• ", List.of()));
                    Map<String, Object> first = itemBlocks.get(0);
                    contentOf(first).forEach(node -> out.add(new LinkedHashMap<>(node)));
                    flatten(itemBlocks.subList(1, itemBlocks.size()), out);
                }
            }
        }
    }

    private static void lineBreak(List<Map<String, Object>> out) {
        if (!out.isEmpty()) {
            out.add(hardBreak());
        }
    }

    private static Map<String, Object> document(List<Map<String, Object>> content) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("type", "doc");
        doc.put("content", content);
        return doc;
    }

    // --- Helpers ------------------------------------------------------------------------------

    private static String typeOf(Map<?, ?> node) {
        return node.get("type") instanceof String type ? type : "";
    }

    private static List<?> content(Map<?, ?> node) {
        return node.get("content") instanceof List<?> list ? list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> contentOf(Map<String, Object> node) {
        Object content = node.get("content");
        if (content instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        List<Map<String, Object>> fresh = new ArrayList<>();
        node.put("content", fresh);
        return fresh;
    }
}
