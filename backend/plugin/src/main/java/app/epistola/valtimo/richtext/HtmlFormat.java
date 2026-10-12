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
import app.epistola.valtimo.richtext.HtmlTokenizer.EndTag;
import app.epistola.valtimo.richtext.HtmlTokenizer.StartTag;
import app.epistola.valtimo.richtext.HtmlTokenizer.Text;
import app.epistola.valtimo.richtext.HtmlTokenizer.Token;

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

/**
 * HTML, as a Form.io WYSIWYG text area (CKEditor or Quill) writes it and as Valtimo case documents
 * filled by one already hold.
 *
 * <p>What the schema can carry is kept: paragraphs, line breaks, nested lists, bold, italic,
 * underline, strikethrough, sub- and superscript, links and text colour — whether written as
 * elements or as inline {@code style}. Everything else keeps its text and loses its form: a heading
 * or a table cell becomes a paragraph; images, scripts and styles are dropped. Quill 2's flat lists
 * ({@code <ol><li data-list="bullet" class="ql-indent-1">}) are rebuilt into nested ones.
 */
public class HtmlFormat implements RichTextFormat {

    public static final String ID = "html";

    private static final Set<String> BLOCKS = Set.of(
            "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "section", "article",
            "header", "footer", "main", "nav", "aside", "address", "figure", "figcaption", "table", "thead",
            "tbody", "tfoot", "tr", "td", "th", "caption", "dl", "dt", "dd", "hr", "center", "form",
            "fieldset", "legend", "details", "summary");
    private static final Set<String> VOID = Set.of(
            "br", "img", "hr", "input", "meta", "link", "wbr", "col", "area", "base", "embed", "source",
            "track", "param");
    private static final Set<String> SKIPPED = Set.of(
            "script", "style", "head", "title", "template", "noscript", "iframe", "object", "svg", "math",
            "select", "button", "textarea");
    private static final Pattern RGB = Pattern.compile(
            "rgba?\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*(?:,\\s*[\\d.]+\\s*)?\\)");
    private static final Pattern HEX = Pattern.compile("#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})");
    /** CKEditor's colour picker writes {@code hsl(30, 75%, 60%)}; CSS also allows spaces and a slash. */
    private static final Pattern HSL = Pattern.compile(
            "hsla?\\(\\s*([\\d.]+)(?:deg)?[\\s,]+([\\d.]+)%[\\s,]+([\\d.]+)%\\s*(?:[,/]\\s*[\\d.]+%?\\s*)?\\)");
    /**
     * The plugins of Form.io's CKEditor build whose content Epistola cannot render. Hiding their
     * buttons is not enough: a loaded plugin still accepts its content from a paste, and
     * Autoformat turns "# " into a heading and "> " into a quote. Removed, they leave the editor
     * schema, so a paste is reduced to paragraphs as it arrives. {@code Font} stays because it
     * carries the colour picker; the background colour it also accepts is dropped on conversion.
     * A name a host's build lacks is ignored by CKEditor.
     */
    static final List<String> UNRENDERABLE_CKEDITOR_PLUGINS = List.of(
            "Heading", "BlockQuote", "Table", "TableToolbar", "Image", "ImageCaption", "ImageStyle",
            "ImageToolbar", "ImageUpload", "ImageResize", "EasyImage", "CKFinder", "CKFinderUploadAdapter",
            "MediaEmbed", "Alignment", "IndentBlock", "FontFamily", "FontSize", "FontBackgroundColor");
    private static final Pattern QUILL_INDENT = Pattern.compile("ql-indent-(\\d{1,2})");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Map<String, Object> toProseMirror(Object value) {
        return toProseMirror(value, new RichTextLosses());
    }

    @Override
    public Map<String, Object> toProseMirror(Object value, RichTextLosses losses) {
        String html = value == null ? "" : String.valueOf(value);
        return new Conversion(HtmlTokenizer.tokenize(html), losses).run();
    }

    @Override
    public Object fromProseMirror(Map<String, Object> doc) {
        return ProseMirrorText.toHtml(doc);
    }

    /**
     * Form.io's text area in WYSIWYG mode, with CKEditor, limited to what Epistola renders: only
     * its buttons on the toolbar, and the plugins for anything else removed, so headings, tables,
     * images or media cannot arrive by paste or autoformat either.
     *
     * <p>CKEditor rather than Quill because only CKEditor's toolbar can be narrowed. Form.io 4
     * deep-merges a component's Quill settings into its default Quill toolbar, index by index, so a
     * shorter toolbar keeps every default button beyond it (headings, fonts, images, video), while
     * CKEditor has no default toolbar to merge into. Form.io's CKEditor build has no underline,
     * strikethrough, sub- or superscript; those still convert when they arrive in HTML from
     * elsewhere, Quill included.
     */
    @Override
    public Map<String, Object> editor(TemplateField.RichTextKind kind) {
        List<String> toolbar = new ArrayList<>(List.of("bold", "italic", "|", "fontColor"));
        if (kind == TemplateField.RichTextKind.BLOCK) {
            toolbar.addAll(List.of("|", "bulletedList", "numberedList", "outdent", "indent"));
        }
        toolbar.addAll(List.of("|", "link", "|", "undo", "redo"));

        Map<String, Object> component = new LinkedHashMap<>();
        component.put("type", "textarea");
        component.put("editor", "ckeditor");
        Map<String, Object> wysiwyg = new LinkedHashMap<>();
        wysiwyg.put("toolbar", toolbar);
        wysiwyg.put("removePlugins", UNRENDERABLE_CKEDITOR_PLUGINS);
        component.put("wysiwyg", wysiwyg);
        component.put("isUploadEnabled", false);
        component.put("rows", kind == TemplateField.RichTextKind.INLINE ? 2 : 6);
        return component;
    }

    /** One pass over the tokens. Not reusable: it carries the state of the walk. */
    private static final class Conversion {

        private record Open(String name, List<Map<String, Object>> marks, boolean quillList, boolean ordered) {
        }

        private final List<Token> tokens;
        private final RichTextLosses losses;
        private final ProseMirrorBuilder builder = new ProseMirrorBuilder();
        private final Deque<Open> open = new ArrayDeque<>();
        /** Per open Quill list container: the ordered flag of each level it has opened. */
        private final Deque<Deque<Boolean>> quillLevels = new ArrayDeque<>();
        private String skipping;
        private int preformatted;

        Conversion(List<Token> tokens, RichTextLosses losses) {
            this.tokens = tokens;
            this.losses = losses;
        }

        Map<String, Object> run() {
            for (int i = 0; i < tokens.size(); i++) {
                Token token = tokens.get(i);
                if (skipping != null) {
                    if (token instanceof EndTag end && end.name().equals(skipping)) {
                        skipping = null;
                    }
                    continue;
                }
                switch (token) {
                    case StartTag start -> start(start, i);
                    case EndTag end -> end(end.name());
                    case Text text -> text(text.text());
                }
            }
            while (!quillLevels.isEmpty()) {
                closeQuillList();
            }
            return builder.build();
        }

        private void start(StartTag tag, int index) {
            String name = tag.name();
            if (SKIPPED.contains(name)) {
                recordSkipped(name);
                if (!tag.selfClosing() && !VOID.contains(name)) {
                    skipping = name;
                }
                return;
            }
            recordUnrenderable(tag);
            if (name.equals("br")) {
                builder.hardBreak();
                return;
            }
            if (BLOCKS.contains(name)) {
                builder.endBlock();
                if (name.equals("pre")) {
                    preformatted++;
                }
            }
            boolean quillList = false;
            switch (name) {
                case "ul", "ol" -> {
                    quillList = isFlatQuillList(index);
                    if (quillList) {
                        builder.endBlock();
                        quillLevels.push(new ArrayDeque<>());
                    } else {
                        builder.openList(name.equals("ol"), startOf(tag));
                    }
                }
                case "li" -> {
                    Open container = innermostList();
                    if (container != null && container.quillList()) {
                        quillItem(tag, container.ordered());
                    } else {
                        builder.openItem();
                    }
                }
                default -> {
                }
            }
            if (!VOID.contains(name) && !tag.selfClosing()) {
                open.push(new Open(name, marksOf(tag, losses), quillList, name.equals("ol")));
            }
        }

        private void end(String name) {
            if (VOID.contains(name)) {
                return;
            }
            // Pop to the matching element, tolerating ones left unclosed inside it. An end tag
            // with no matching start is ignored, as a browser would.
            if (open.stream().noneMatch(element -> element.name().equals(name))) {
                return;
            }
            Open element;
            do {
                element = open.pop();
            } while (!element.name().equals(name));

            if (BLOCKS.contains(name)) {
                builder.endBlock();
                if (name.equals("pre")) {
                    preformatted = Math.max(0, preformatted - 1);
                }
            }
            switch (name) {
                case "ul", "ol" -> {
                    if (element.quillList()) {
                        closeQuillList();
                    } else {
                        builder.closeList();
                    }
                }
                case "li" -> {
                    Open container = innermostList();
                    if (container == null || !container.quillList()) {
                        builder.closeItem();
                    } else {
                        // A Quill item stays open: the next, deeper item nests inside it.
                        builder.endBlock();
                    }
                }
                default -> {
                }
            }
        }

        private void text(String raw) {
            // U+FEFF is Quill's cursor placeholder, never part of what was written.
            String text = raw.replace("﻿", "");
            List<Map<String, Object>> marks = currentMarks();
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
         * Rebuild the nesting: close levels deeper than this item, switch type at its level if it
         * differs, open levels down to it, then start the item.
         */
        private void quillItem(StartTag tag, boolean containerOrdered) {
            Deque<Boolean> levels = quillLevels.peek();
            String type = tag.attributes().get("data-list");
            boolean ordered = type == null ? containerOrdered : "ordered".equals(type);
            int depth = 1 + quillIndent(tag);
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
        }

        private void closeQuillList() {
            Deque<Boolean> levels = quillLevels.pop();
            while (!levels.isEmpty()) {
                builder.closeList();
                levels.pop();
            }
        }

        /**
         * Whether the list starting at {@code index} is one of Quill's flat ones: an item of its
         * own (not of a list nested in it) carries {@code data-list} or a {@code ql-indent-N}.
         */
        private boolean isFlatQuillList(int index) {
            int depth = 0;
            for (int i = index + 1; i < tokens.size(); i++) {
                Token token = tokens.get(i);
                if (token instanceof StartTag start) {
                    if (start.name().equals("ul") || start.name().equals("ol")) {
                        depth++;
                    } else if (depth == 0 && start.name().equals("li")
                            && (start.attributes().containsKey("data-list") || quillIndent(start) > 0)) {
                        return true;
                    }
                } else if (token instanceof EndTag end && (end.name().equals("ul") || end.name().equals("ol"))) {
                    if (depth-- == 0) {
                        return false;
                    }
                }
            }
            return false;
        }

        private Open innermostList() {
            for (Open element : open) {
                if (element.name().equals("ul") || element.name().equals("ol")) {
                    return element;
                }
            }
            return null;
        }

        /** What a skipped element was, for the report; page furniture with no content is not. */
        private void recordSkipped(String name) {
            switch (name) {
                case "script", "style" -> losses.lost("script or style");
                case "iframe", "object", "svg", "math" -> losses.lost("embedded content");
                case "select", "button", "textarea" -> losses.lost("form control");
                default -> {
                    // head, title, template, noscript: never content someone wrote
                }
            }
        }

        /** Elements whose text is kept but whose form the schema cannot carry. */
        private void recordUnrenderable(StartTag tag) {
            switch (tag.name()) {
                case "h1", "h2", "h3", "h4", "h5", "h6" -> losses.lost("heading");
                case "table" -> losses.lost("table");
                case "blockquote" -> losses.lost("block quote");
                case "hr" -> losses.lost("horizontal rule");
                case "img" -> losses.lost("image");
                case "video", "audio" -> losses.lost("media");
                default -> {
                }
            }
            String classes = tag.attributes().get("class");
            if (classes != null) {
                // Quill keeps these as classes rather than styles.
                if (classes.contains("ql-align-")) {
                    losses.lost("alignment");
                }
                if (classes.contains("ql-size-") || classes.contains("ql-font-")) {
                    losses.lost("font");
                }
                if (!tag.name().equals("li") && quillIndent(tag) > 0) {
                    losses.lost("indentation");
                }
            }
        }

        private List<Map<String, Object>> currentMarks() {
            List<Map<String, Object>> marks = new ArrayList<>();
            Set<String> types = new java.util.HashSet<>();
            // Outermost first, so an inner element's colour or link does not get overridden.
            var iterator = open.descendingIterator();
            while (iterator.hasNext()) {
                for (Map<String, Object> mark : iterator.next().marks()) {
                    String type = (String) mark.get("type");
                    if (!types.add(type)) {
                        // The innermost of two links or colours is the one that applies.
                        marks.removeIf(existing -> existing.get("type").equals(type));
                    }
                    marks.add(mark);
                }
            }
            return marks;
        }

        private static List<Map<String, Object>> marksOf(StartTag tag, RichTextLosses losses) {
            List<Map<String, Object>> marks = new ArrayList<>();
            switch (tag.name()) {
                case "strong", "b" -> marks.add(mark("strong"));
                case "em", "i" -> marks.add(mark("em"));
                case "u", "ins" -> marks.add(mark("underline"));
                case "s", "strike", "del" -> marks.add(mark("strikethrough"));
                case "sub" -> marks.add(mark("subscript"));
                case "sup" -> marks.add(mark("superscript"));
                case "a" -> {
                    String href = tag.attributes().get("href");
                    if (href != null && !ProseMirrorSanitizer.isAllowedHref(href)) {
                        losses.lost("link");
                    }
                    if (href != null && ProseMirrorSanitizer.isAllowedHref(href)) {
                        Map<String, Object> link = mark("link");
                        Map<String, Object> attrs = new LinkedHashMap<>();
                        attrs.put("href", href.trim());
                        String title = tag.attributes().get("title");
                        if (title != null) {
                            attrs.put("title", title);
                        }
                        link.put("attrs", attrs);
                        marks.add(link);
                    }
                }
                case "font" -> {
                    addColor(marks, tag.attributes().get("color"), losses);
                    if (tag.attributes().containsKey("face") || tag.attributes().containsKey("size")) {
                        losses.lost("font");
                    }
                }
                default -> {
                }
            }
            String style = tag.attributes().get("style");
            if (style != null) {
                styleMarks(style, marks, losses);
            }
            return marks;
        }

        private static void styleMarks(String style, List<Map<String, Object>> marks, RichTextLosses losses) {
            for (String declaration : style.split(";")) {
                int colon = declaration.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String property = declaration.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = declaration.substring(colon + 1).trim().toLowerCase(Locale.ROOT);
                switch (property) {
                    case "color" -> addColor(marks, value, losses);
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
                            marks.add(mark("strong"));
                        }
                    }
                    case "font-style" -> {
                        if (value.equals("italic") || value.equals("oblique")) {
                            marks.add(mark("em"));
                        }
                    }
                    case "text-decoration", "text-decoration-line" -> {
                        if (value.contains("underline")) {
                            marks.add(mark("underline"));
                        }
                        if (value.contains("line-through")) {
                            marks.add(mark("strikethrough"));
                        }
                    }
                    case "vertical-align" -> {
                        if (value.equals("sub")) {
                            marks.add(mark("subscript"));
                        } else if (value.equals("super")) {
                            marks.add(mark("superscript"));
                        }
                    }
                    default -> {
                    }
                }
            }
        }

        /** A colour as {@code #rgb} or {@code #rrggbb}, the only forms the schema takes. */
        private static void addColor(List<Map<String, Object>> marks, String value, RichTextLosses losses) {
            if (value == null) {
                return;
            }
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
                Map<String, Object> mark = mark("textStyle");
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

        private static Integer startOf(StartTag tag) {
            String start = tag.attributes().get("start");
            if (start == null) {
                return null;
            }
            try {
                return Integer.parseInt(start.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private static int quillIndent(StartTag tag) {
            String classes = tag.attributes().get("class");
            if (classes == null) {
                return 0;
            }
            Matcher matcher = QUILL_INDENT.matcher(classes);
            return matcher.find() ? Math.min(Integer.parseInt(matcher.group(1)), 16) : 0;
        }

        private static Map<String, Object> mark(String type) {
            Map<String, Object> mark = new LinkedHashMap<>();
            mark.put("type", type);
            return mark;
        }
    }
}
