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
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Writes a sanitized ProseMirror document back out as plain text, HTML or markdown: the reverse
 * direction of the shipped formats, used to show an existing document in a format's editor.
 *
 * <p>Each target keeps what it can express. Plain text keeps lines, paragraphs and list markers;
 * markdown adds strong, em, strikethrough and links; HTML keeps everything the schema allows.
 */
final class ProseMirrorText {

    private static final Pattern MARKDOWN_SPECIAL = Pattern.compile("([\\\\*_`\\[\\]~<])");
    private static final Pattern MARKDOWN_LINE_START = Pattern.compile("^(\\s*)([#>+-]|\\d+[.)])(?=\\s|$)");

    private ProseMirrorText() {
    }

    // --- Plain text -------------------------------------------------------------------------

    static String toPlainText(Map<String, Object> doc) {
        List<String> blocks = new ArrayList<>();
        for (Map<String, Object> block : content(doc)) {
            String text = plainBlock(block, "");
            if (!text.isEmpty()) {
                blocks.add(text);
            }
        }
        return String.join("\n\n", blocks);
    }

    private static String plainBlock(Map<String, Object> block, String indent) {
        if ("paragraph".equals(block.get("type"))) {
            StringBuilder out = new StringBuilder();
            for (Map<String, Object> node : content(block)) {
                if ("hard_break".equals(node.get("type"))) {
                    out.append('\n').append(indent);
                } else if (node.get("text") instanceof String text) {
                    out.append(text);
                }
            }
            return out.toString();
        }
        boolean ordered = "ordered_list".equals(block.get("type"));
        int number = start(block);
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> item : content(block)) {
            String marker = ordered ? (number++) + ". " : "- ";
            String nested = indent + " ".repeat(marker.length());
            List<String> parts = new ArrayList<>();
            for (Map<String, Object> child : content(item)) {
                parts.add(plainBlock(child, nested));
            }
            String first = parts.isEmpty() ? "" : parts.get(0);
            StringBuilder line = new StringBuilder(indent).append(marker).append(first);
            for (String part : parts.subList(Math.min(1, parts.size()), parts.size())) {
                line.append('\n').append(part.startsWith(nested) ? part : nested + part);
            }
            lines.add(line.toString());
        }
        return String.join("\n", lines);
    }

    // --- HTML -------------------------------------------------------------------------------

    static String toHtml(Map<String, Object> doc) {
        StringBuilder out = new StringBuilder();
        for (Map<String, Object> block : content(doc)) {
            htmlBlock(block, out);
        }
        return out.toString();
    }

    private static void htmlBlock(Map<String, Object> block, StringBuilder out) {
        String type = String.valueOf(block.get("type"));
        if (type.equals("paragraph")) {
            out.append("<p>");
            htmlInline(content(block), out);
            out.append("</p>");
            return;
        }
        boolean ordered = type.equals("ordered_list");
        int start = start(block);
        out.append(ordered ? (start > 1 ? "<ol start=\"" + start + "\">" : "<ol>") : "<ul>");
        for (Map<String, Object> item : content(block)) {
            out.append("<li>");
            for (Map<String, Object> child : content(item)) {
                htmlBlock(child, out);
            }
            out.append("</li>");
        }
        out.append(ordered ? "</ol>" : "</ul>");
    }

    private static void htmlInline(List<Map<String, Object>> nodes, StringBuilder out) {
        List<Map<String, Object>> active = new ArrayList<>();
        for (Map<String, Object> node : nodes) {
            List<Map<String, Object>> marks = marks(node);
            int keep = commonPrefix(active, marks);
            for (int i = active.size() - 1; i >= keep; i--) {
                out.append(htmlClose(active.remove(i)));
            }
            for (Map<String, Object> mark : marks) {
                if (!active.contains(mark)) {
                    out.append(htmlOpen(mark));
                    active.add(mark);
                }
            }
            if ("hard_break".equals(node.get("type"))) {
                out.append("<br>");
            } else if (node.get("text") instanceof String text) {
                out.append(escapeHtml(text));
            }
        }
        for (int i = active.size() - 1; i >= 0; i--) {
            out.append(htmlClose(active.get(i)));
        }
    }

    private static String htmlOpen(Map<String, Object> mark) {
        Map<?, ?> attrs = mark.get("attrs") instanceof Map<?, ?> map ? map : Map.of();
        return switch (String.valueOf(mark.get("type"))) {
            case "strong" -> "<strong>";
            case "em" -> "<em>";
            case "underline" -> "<u>";
            case "strikethrough" -> "<s>";
            case "subscript" -> "<sub>";
            case "superscript" -> "<sup>";
            case "link" -> "<a href=\"" + escapeHtml(String.valueOf(attrs.get("href"))) + "\">";
            case "textStyle" -> "<span style=\"color: " + escapeHtml(String.valueOf(attrs.get("color"))) + "\">";
            default -> "";
        };
    }

    private static String htmlClose(Map<String, Object> mark) {
        return switch (String.valueOf(mark.get("type"))) {
            case "strong" -> "</strong>";
            case "em" -> "</em>";
            case "underline" -> "</u>";
            case "strikethrough" -> "</s>";
            case "subscript" -> "</sub>";
            case "superscript" -> "</sup>";
            case "link" -> "</a>";
            case "textStyle" -> "</span>";
            default -> "";
        };
    }

    static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // --- Markdown ---------------------------------------------------------------------------

    static String toMarkdown(Map<String, Object> doc) {
        List<String> blocks = new ArrayList<>();
        for (Map<String, Object> block : content(doc)) {
            String text = markdownBlock(block, "");
            if (!text.isEmpty()) {
                blocks.add(text);
            }
        }
        return String.join("\n\n", blocks);
    }

    private static String markdownBlock(Map<String, Object> block, String indent) {
        if ("paragraph".equals(block.get("type"))) {
            return markdownInline(content(block), indent);
        }
        boolean ordered = "ordered_list".equals(block.get("type"));
        int number = start(block);
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> item : content(block)) {
            String marker = ordered ? (number++) + ". " : "- ";
            String nested = indent + " ".repeat(marker.length());
            StringBuilder line = new StringBuilder(indent).append(marker);
            List<Map<String, Object>> children = content(item);
            for (int i = 0; i < children.size(); i++) {
                Map<String, Object> child = children.get(i);
                String text = markdownBlock(child, nested);
                if (i == 0) {
                    line.append(text);
                } else if ("paragraph".equals(child.get("type"))) {
                    line.append("\n\n").append(nested).append(text);
                } else {
                    line.append('\n').append(text);
                }
            }
            lines.add(line.toString());
        }
        return String.join("\n", lines);
    }

    /**
     * Marks open and close as they change between nodes, so overlapping marks nest instead of
     * being written once per node. Whitespace is kept outside the delimiters, where markdown needs
     * it to recognise them.
     */
    private static String markdownInline(List<Map<String, Object>> nodes, String indent) {
        StringBuilder out = new StringBuilder();
        List<Map<String, Object>> active = new ArrayList<>();
        String pendingSpace = "";
        for (Map<String, Object> node : nodes) {
            if ("hard_break".equals(node.get("type"))) {
                closeAll(active, out);
                out.append("\\\n").append(indent);
                pendingSpace = "";
                continue;
            }
            if (!(node.get("text") instanceof String text)) {
                continue;
            }
            String core = text.strip();
            if (core.isEmpty()) {
                pendingSpace += text;
                continue;
            }
            List<Map<String, Object>> marks = marks(node).stream().filter(ProseMirrorText::inMarkdown).toList();
            int at = text.indexOf(core);
            String leading = text.substring(0, at);
            String trailing = text.substring(at + core.length());
            int keep = commonPrefix(active, marks);
            for (int i = active.size() - 1; i >= keep; i--) {
                out.append(markdownClose(active.remove(i)));
            }
            out.append(pendingSpace).append(leading);
            for (Map<String, Object> mark : marks) {
                if (!active.contains(mark)) {
                    out.append(markdownOpen(mark));
                    active.add(mark);
                }
            }
            out.append(escapeMarkdown(core));
            pendingSpace = trailing;
        }
        closeAll(active, out);
        out.append(pendingSpace);
        String result = out.toString();
        return MARKDOWN_LINE_START.matcher(result).replaceFirst("$1\\\\$2");
    }

    private static void closeAll(List<Map<String, Object>> active, StringBuilder out) {
        for (int i = active.size() - 1; i >= 0; i--) {
            out.append(markdownClose(active.remove(i)));
        }
    }

    private static boolean inMarkdown(Map<String, Object> mark) {
        return List.of("strong", "em", "strikethrough", "link").contains(String.valueOf(mark.get("type")));
    }

    private static String markdownOpen(Map<String, Object> mark) {
        return switch (String.valueOf(mark.get("type"))) {
            case "strong" -> "**";
            case "em" -> "*";
            case "strikethrough" -> "~~";
            case "link" -> "[";
            default -> "";
        };
    }

    private static String markdownClose(Map<String, Object> mark) {
        Map<?, ?> attrs = mark.get("attrs") instanceof Map<?, ?> map ? map : Map.of();
        return switch (String.valueOf(mark.get("type"))) {
            case "strong" -> "**";
            case "em" -> "*";
            case "strikethrough" -> "~~";
            case "link" -> "](" + String.valueOf(attrs.get("href")).replace(")", "%29").replace(" ", "%20") + ")";
            default -> "";
        };
    }

    private static String escapeMarkdown(String text) {
        return MARKDOWN_SPECIAL.matcher(text).replaceAll("\\\\$1");
    }

    // --- Helpers ----------------------------------------------------------------------------

    private static int commonPrefix(List<Map<String, Object>> active, List<Map<String, Object>> marks) {
        int i = 0;
        while (i < active.size() && marks.contains(active.get(i))) {
            i++;
        }
        return i;
    }

    private static int start(Map<String, Object> list) {
        return list.get("attrs") instanceof Map<?, ?> attrs && attrs.get("order") instanceof Number order
                ? order.intValue()
                : 1;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> content(Map<String, Object> node) {
        return node.get("content") instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> marks(Map<String, Object> node) {
        return node.get("marks") instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
