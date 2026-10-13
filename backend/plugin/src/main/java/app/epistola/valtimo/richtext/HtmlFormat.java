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

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static app.epistola.valtimo.richtext.Nodes.node;

/**
 * HTML, as a Form.io WYSIWYG text area (CKEditor or Quill) writes it and as Valtimo case documents
 * filled by one already hold.
 *
 * <p>Parsed with jsoup, which follows the HTML5 parsing rules — the ones that decide what pasted
 * Word or Outlook markup, unclosed paragraphs and misnested tags mean — and knows every named
 * character reference. This class only walks the result.
 *
 * <p>What the schema can carry is kept: paragraphs, line breaks, nested lists, bold, italic,
 * underline, strikethrough, sub- and superscript, links and text colour — whether written as
 * elements or as inline {@code style}. Everything else keeps its text and loses its form, and is
 * recorded as lost: a heading or a table cell becomes a paragraph; images, scripts and styles are
 * dropped. Quill's flat lists ({@code <ol><li data-list="bullet" class="ql-indent-1">}) are rebuilt
 * into nested ones.
 */
public class HtmlFormat implements RichTextFormat {

    public static final String ID = "html";

    private static final Set<String> BLOCKS = Set.of(
            "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "section", "article",
            "header", "footer", "main", "nav", "aside", "address", "figure", "figcaption", "table", "thead",
            "tbody", "tfoot", "tr", "td", "th", "caption", "dl", "dt", "dd", "hr", "center", "form",
            "fieldset", "legend", "details", "summary");
    private static final Set<String> SKIPPED = Set.of(
            "script", "style", "head", "title", "template", "noscript", "iframe", "object", "embed", "svg",
            "math", "select", "button", "textarea", "input");
    private static final Pattern RGB = Pattern.compile(
            "rgba?\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*(?:,\\s*[\\d.]+\\s*)?\\)");
    private static final Pattern HEX = Pattern.compile("#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})");
    /** CKEditor's colour picker writes {@code hsl(30, 75%, 60%)}; CSS also allows spaces and a slash. */
    private static final Pattern HSL = Pattern.compile(
            "hsla?\\(\\s*([\\d.]+)(?:deg)?[\\s,]+([\\d.]+)%[\\s,]+([\\d.]+)%\\s*(?:[,/]\\s*[\\d.]+%?\\s*)?\\)");
    private static final Pattern QUILL_INDENT = Pattern.compile("ql-indent-(\\d{1,2})");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Map<String, Object> toProseMirror(Object value, RichTextLosses losses) {
        String html = value == null ? "" : String.valueOf(value);
        Conversion conversion = new Conversion(losses);
        conversion.children(Jsoup.parseBodyFragment(html).body(), List.of());
        return conversion.builder.build();
    }

    @Override
    public Object fromProseMirror(Map<String, Object> doc) {
        return ProseMirrorText.toHtml(doc);
    }

    /** One walk over a parsed fragment. Not reusable: it carries the state of the walk. */
    private static final class Conversion {

        private final RichTextLosses losses;
        private final ProseMirrorBuilder builder = new ProseMirrorBuilder();
        private int preformatted;

        Conversion(RichTextLosses losses) {
            this.losses = losses;
        }

        void children(Element parent, List<Map<String, Object>> marks) {
            for (Node child : parent.childNodes()) {
                if (child instanceof TextNode text) {
                    text(text.getWholeText(), marks);
                } else if (child instanceof Element element) {
                    element(element, marks);
                }
                // Comments, and the data of a script or style, are not text of the letter.
            }
        }

        private void element(Element element, List<Map<String, Object>> outer) {
            String name = element.normalName();
            if (SKIPPED.contains(name)) {
                recordSkipped(name);
                return;
            }
            recordUnrenderable(element);
            if (name.equals("br")) {
                builder.hardBreak();
                return;
            }
            List<Map<String, Object>> marks = withMarks(outer, element);
            boolean block = BLOCKS.contains(name);
            if (block) {
                builder.endBlock();
            }
            if (name.equals("pre")) {
                preformatted++;
            }
            switch (name) {
                case "ul", "ol" -> {
                    if (isFlatQuillList(element)) {
                        quillList(element, marks);
                    } else {
                        builder.openList(name.equals("ol"), startOf(element));
                        children(element, marks);
                        builder.closeList();
                    }
                }
                case "li" -> {
                    builder.openItem();
                    children(element, marks);
                    builder.closeItem();
                }
                default -> children(element, marks);
            }
            if (name.equals("pre")) {
                preformatted--;
            }
            if (block) {
                builder.endBlock();
            }
        }

        private void text(String raw, List<Map<String, Object>> marks) {
            // U+FEFF is Quill's cursor placeholder, never part of what was written.
            String text = raw.replace("﻿", "");
            if (preformatted > 0) {
                String[] lines = text.split("\r?\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    if (i > 0) {
                        builder.hardBreak();
                    }
                    builder.text(lines[i], marks);
                }
                return;
            }
            builder.text(text.replaceAll("[ \\t\\n\\r\\f]+", " "), marks);
        }

        /**
         * Quill writes a list flat, giving each item its depth as a {@code ql-indent-N} class —
         * and Quill 2 also its type, as {@code data-list}, inside one {@code <ol>} for every list.
         * Rebuild the nesting: close levels deeper than an item, switch type at its level if it
         * differs, open levels down to it, then start the item. An item stays open after its text,
         * because the next, deeper item nests inside it.
         */
        private void quillList(Element container, List<Map<String, Object>> marks) {
            Deque<Boolean> levels = new ArrayDeque<>();
            boolean containerOrdered = container.normalName().equals("ol");
            for (Node child : container.childNodes()) {
                if (!(child instanceof Element item) || !item.normalName().equals("li")) {
                    continue;
                }
                String type = item.attr("data-list");
                boolean ordered = type.isEmpty() ? containerOrdered : type.equals("ordered");
                int depth = 1 + quillIndent(item);
                while (levels.size() > depth) {
                    builder.closeList();
                    levels.pop();
                }
                if (levels.size() == depth && levels.peek() != ordered) {
                    builder.closeList();
                    levels.pop();
                }
                while (levels.size() < depth) {
                    builder.openList(ordered, null);
                    levels.push(ordered);
                }
                builder.openItem();
                children(item, withMarks(marks, item));
                builder.endBlock();
            }
            while (!levels.isEmpty()) {
                builder.closeList();
                levels.pop();
            }
        }

        /** Whether one of a list's own items carries Quill's {@code data-list} or indent class. */
        private static boolean isFlatQuillList(Element list) {
            return list.children().stream().anyMatch(item -> item.normalName().equals("li")
                    && (item.hasAttr("data-list") || quillIndent(item) > 0));
        }

        /** What a skipped element was, for the report; page furniture with no content is not. */
        private void recordSkipped(String name) {
            switch (name) {
                case "script", "style" -> losses.lost("script or style");
                case "iframe", "object", "embed", "svg", "math" -> losses.lost("embedded content");
                case "select", "button", "textarea", "input" -> losses.lost("form control");
                default -> {
                    // head, title, template, noscript: never content someone wrote
                }
            }
        }

        /** Elements whose text is kept but whose form the schema cannot carry. */
        private void recordUnrenderable(Element element) {
            switch (element.normalName()) {
                case "h1", "h2", "h3", "h4", "h5", "h6" -> losses.lost("heading");
                case "table" -> losses.lost("table");
                case "blockquote" -> losses.lost("block quote");
                case "hr" -> losses.lost("horizontal rule");
                case "img" -> losses.lost("image");
                case "video", "audio" -> losses.lost("media");
                default -> {
                }
            }
            String classes = element.className();
            // Quill keeps these as classes rather than styles.
            if (classes.contains("ql-align-")) {
                losses.lost("alignment");
            }
            if (classes.contains("ql-size-") || classes.contains("ql-font-")) {
                losses.lost("font");
            }
            if (!element.normalName().equals("li") && quillIndent(element) > 0) {
                losses.lost("indentation");
            }
        }

        /**
         * The marks in force inside {@code element}: those around it, plus its own. Of two links or
         * two colours the innermost applies, as it does in a browser.
         */
        private List<Map<String, Object>> withMarks(List<Map<String, Object>> outer, Element element) {
            List<Map<String, Object>> own = marksOf(element);
            if (own.isEmpty()) {
                return outer;
            }
            List<Map<String, Object>> marks = new ArrayList<>(outer);
            for (Map<String, Object> mark : own) {
                marks.removeIf(existing -> existing.get("type").equals(mark.get("type")));
                marks.add(mark);
            }
            return marks;
        }

        private List<Map<String, Object>> marksOf(Element element) {
            List<Map<String, Object>> marks = new ArrayList<>();
            switch (element.normalName()) {
                case "strong", "b" -> marks.add(node("strong"));
                case "em", "i" -> marks.add(node("em"));
                case "u", "ins" -> marks.add(node("underline"));
                case "s", "strike", "del" -> marks.add(node("strikethrough"));
                case "sub" -> marks.add(node("subscript"));
                case "sup" -> marks.add(node("superscript"));
                case "a" -> {
                    if (element.hasAttr("href")) {
                        String href = element.attr("href");
                        if (ProseMirrorSanitizer.isAllowedHref(href)) {
                            Map<String, Object> link = node("link");
                            Map<String, Object> attrs = new LinkedHashMap<>();
                            attrs.put("href", href.trim());
                            if (element.hasAttr("title")) {
                                attrs.put("title", element.attr("title"));
                            }
                            link.put("attrs", attrs);
                            marks.add(link);
                        } else {
                            losses.lost("link");
                        }
                    }
                }
                case "font" -> {
                    if (element.hasAttr("color")) {
                        addColor(marks, element.attr("color"));
                    }
                    if (element.hasAttr("face") || element.hasAttr("size")) {
                        losses.lost("font");
                    }
                }
                default -> {
                }
            }
            if (element.hasAttr("style")) {
                styleMarks(element.attr("style"), marks);
            }
            return marks;
        }

        private void styleMarks(String style, List<Map<String, Object>> marks) {
            for (String declaration : style.split(";")) {
                int colon = declaration.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String property = declaration.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = declaration.substring(colon + 1).trim().toLowerCase(Locale.ROOT);
                switch (property) {
                    case "color" -> addColor(marks, value);
                    case "text-align" -> {
                        if (!value.equals("left") && !value.equals("start")) {
                            losses.lost("alignment");
                        }
                    }
                    case "font-size", "font-family", "font" -> losses.lost("font");
                    case "background", "background-color" -> {
                        if (!value.equals("transparent") && !value.equals("none")) {
                            losses.lost("background colour");
                        }
                    }
                    case "margin-left", "padding-left", "text-indent" -> {
                        if (!value.matches("0(\\.0+)?[a-z%]*")) {
                            losses.lost("indentation");
                        }
                    }
                    case "font-weight" -> {
                        if (value.equals("bold") || value.equals("bolder") || value.matches("[6-9]00")) {
                            marks.add(node("strong"));
                        }
                    }
                    case "font-style" -> {
                        if (value.equals("italic") || value.equals("oblique")) {
                            marks.add(node("em"));
                        }
                    }
                    case "text-decoration", "text-decoration-line" -> {
                        if (value.contains("underline")) {
                            marks.add(node("underline"));
                        }
                        if (value.contains("line-through")) {
                            marks.add(node("strikethrough"));
                        }
                    }
                    case "vertical-align" -> {
                        if (value.equals("sub")) {
                            marks.add(node("subscript"));
                        } else if (value.equals("super")) {
                            marks.add(node("superscript"));
                        }
                    }
                    default -> {
                    }
                }
            }
        }

        /** A colour as {@code #rgb} or {@code #rrggbb}, the only forms the schema takes. */
        private void addColor(List<Map<String, Object>> marks, String value) {
            String color = null;
            Matcher hex = HEX.matcher(value.trim());
            Matcher rgb = RGB.matcher(value.trim());
            Matcher hsl = HSL.matcher(value.trim());
            if (hex.matches()) {
                color = "#" + hex.group(1).toLowerCase(Locale.ROOT);
            } else if (rgb.matches()) {
                StringBuilder out = new StringBuilder("#");
                for (int group = 1; group <= 3; group++) {
                    out.append(String.format("%02x", Math.min(255, Integer.parseInt(rgb.group(group)))));
                }
                color = out.toString();
            } else if (hsl.matches()) {
                color = hslToHex(Double.parseDouble(hsl.group(1)), Double.parseDouble(hsl.group(2)),
                        Double.parseDouble(hsl.group(3)));
            }
            if (color == null) {
                // A named colour, or a notation the schema cannot take: the text keeps its default.
                losses.lost("colour");
            } else {
                Map<String, Object> mark = node("textStyle");
                mark.put("attrs", new LinkedHashMap<>(Map.of("color", color)));
                marks.add(mark);
            }
        }

        /** The CSS Color 4 conversion from HSL to sRGB, rounded to the nearest channel value. */
        private static String hslToHex(double hue, double saturation, double lightness) {
            double s = Math.min(saturation, 100) / 100;
            double l = Math.min(lightness, 100) / 100;
            double a = s * Math.min(l, 1 - l);
            StringBuilder out = new StringBuilder("#");
            for (int n : new int[]{0, 8, 4}) {
                double k = (n + (hue % 360) / 30) % 12;
                double channel = l - a * Math.max(-1, Math.min(Math.min(k - 3, 9 - k), 1));
                out.append(String.format("%02x", (int) Math.round(channel * 255)));
            }
            return out.toString();
        }

        private static Integer startOf(Element list) {
            if (!list.hasAttr("start")) {
                return null;
            }
            try {
                return Integer.parseInt(list.attr("start").trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private static int quillIndent(Element element) {
            Matcher matcher = QUILL_INDENT.matcher(element.className());
            return matcher.find() ? Math.min(Integer.parseInt(matcher.group(1)), 16) : 0;
        }


    }
}
