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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown, in the subset that maps onto what Epistola renders: paragraphs, hard line breaks
 * (two trailing spaces or a backslash), nested bullet and ordered lists, {@code **strong**},
 * {@code *em*}, {@code ~~strikethrough~~} and links ({@code [text](url)}, {@code <url>}).
 *
 * <p>The rest of CommonMark keeps its text: a heading, a block quote or a fenced code block
 * becomes a paragraph, inline code its plain text. Underline, sub- and superscript and colour have
 * no markdown syntax and so never arise. Written here rather than taken from a library (ADR 0008
 * adds no dependencies); it aims to read what people and common editors write, not to pass the
 * CommonMark test suite.
 */
public class MarkdownFormat implements RichTextFormat {

    public static final String ID = "markdown";

    private static final Pattern FENCE = Pattern.compile("^\\s{0,3}(`{3,}|~{3,}).*$");
    private static final Pattern THEMATIC_BREAK = Pattern.compile("^\\s{0,3}([-*_])(?:\\s*\\1){2,}\\s*$");
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}#{1,6}(?:\\s+(.*?))?(?:\\s+#+)?\\s*$");
    private static final Pattern BLOCK_QUOTE = Pattern.compile("^\\s{0,3}>\\s?(.*)$");
    private static final Pattern LIST_ITEM = Pattern.compile("^(\\s*)([-*+]|(\\d{1,9})[.)])(?:(\\s+)(.*))?$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

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
        String text = value == null ? "" : String.valueOf(value);
        return new Conversion(text.replace("\u0000", ""), losses).run();
    }

    @Override
    public Object fromProseMirror(Map<String, Object> doc) {
        return ProseMirrorText.toMarkdown(doc);
    }

    /** One pass over the text. Not reusable: it carries the state of the walk. */
    private static final class Conversion {

        /** A list being built: where its markers start, and where an item's content starts. */
        private record Level(int indent, int contentIndent, boolean ordered) {
        }

        /** Stands in for a hard line break between lines handed to the inline parser. */
        private static final char HARD_BREAK = '\u0000';

        private final String[] lines;
        private final RichTextLosses losses;
        private final ProseMirrorBuilder builder = new ProseMirrorBuilder();
        private final Deque<Level> lists = new ArrayDeque<>();
        private final List<String> paragraph = new ArrayList<>();
        private final List<String> code = new ArrayList<>();
        private String fence;
        private boolean blankBefore;
        private boolean inQuote;

        Conversion(String text, RichTextLosses losses) {
            this.lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
            this.losses = losses;
        }

        Map<String, Object> run() {
            for (String raw : lines) {
                line(raw);
            }
            if (fence != null) {
                flushCode();
            }
            flushParagraph();
            while (!lists.isEmpty()) {
                builder.closeList();
                lists.pop();
            }
            return builder.build();
        }

        private void line(String raw) {
            if (fence != null) {
                if (raw.strip().startsWith(fence)) {
                    flushCode();
                    fence = null;
                } else {
                    code.add(raw);
                }
                return;
            }
            String line = raw;
            Matcher quote = BLOCK_QUOTE.matcher(line);
            if (quote.matches() && !inQuote) {
                losses.lost("block quote");
            }
            inQuote = quote.matches();
            while (quote.matches()) {
                line = quote.group(1);
                quote = BLOCK_QUOTE.matcher(line);
            }
            if (line.isBlank()) {
                flushParagraph();
                blankBefore = true;
                return;
            }
            Matcher fenceStart = FENCE.matcher(line);
            if (fenceStart.matches()) {
                flushParagraph();
                losses.lost("code block");
                fence = fenceStart.group(1);
                blankBefore = false;
                return;
            }
            if (THEMATIC_BREAK.matcher(line).matches()) {
                flushParagraph();
                losses.lost("horizontal rule");
                blankBefore = false;
                return;
            }
            int indent = indentOf(line);
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                flushParagraph();
                losses.lost("heading");
                closeListsDeeperThan(indent);
                paragraph.add(heading.group(1) == null ? "" : heading.group(1));
                flushParagraph();
                blankBefore = false;
                return;
            }
            Matcher item = LIST_ITEM.matcher(line);
            if (item.matches()) {
                listItem(item, indent);
                blankBefore = false;
                return;
            }
            if (blankBefore) {
                // After a blank line, text indented less than an item's content is no longer part
                // of it. Without one, it is a lazy continuation of the item's paragraph.
                closeListsDeeperThan(indent);
            }
            paragraph.add(line);
            blankBefore = false;
        }

        private void listItem(Matcher item, int indent) {
            flushParagraph();
            boolean ordered = item.group(3) != null;
            Integer start = ordered ? Integer.valueOf(item.group(3)) : null;
            String spacing = item.group(4) == null ? " " : item.group(4);
            int contentIndent = indent + item.group(2).length() + (spacing.length() > 4 ? 1 : spacing.length());

            while (!lists.isEmpty() && indent < lists.peek().indent()) {
                builder.closeList();
                lists.pop();
            }
            boolean sibling = !lists.isEmpty() && indent < lists.peek().contentIndent();
            if (sibling && lists.peek().ordered() != ordered) {
                builder.closeList();
                lists.pop();
                sibling = false;
            }
            if (!sibling) {
                builder.openList(ordered, start);
                lists.push(new Level(indent, contentIndent, ordered));
            }
            builder.openItem();
            String content = item.group(5);
            if (content != null && !content.isBlank()) {
                paragraph.add(content);
            }
        }

        private void closeListsDeeperThan(int indent) {
            while (!lists.isEmpty() && indent < lists.peek().contentIndent()) {
                builder.closeList();
                lists.pop();
            }
        }

        /** Hand the paragraph's lines to the inline parser as one run, so emphasis may span them. */
        private void flushParagraph() {
            if (paragraph.isEmpty()) {
                return;
            }
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < paragraph.size(); i++) {
                String line = paragraph.get(i).stripLeading();
                boolean hard = false;
                if (line.endsWith("  ")) {
                    hard = true;
                } else if (line.endsWith("\\") && !line.endsWith("\\\\")) {
                    hard = true;
                    line = line.substring(0, line.length() - 1);
                }
                joined.append(line.stripTrailing());
                if (i < paragraph.size() - 1) {
                    joined.append(hard ? HARD_BREAK : '\n');
                }
            }
            paragraph.clear();
            inline(joined.toString(), List.of());
            builder.endBlock();
        }

        private void flushCode() {
            flushParagraph();
            for (int i = 0; i < code.size(); i++) {
                if (i > 0) {
                    builder.hardBreak();
                }
                builder.text(code.get(i), List.of());
            }
            code.clear();
            builder.endBlock();
        }

        // --- Inline ---------------------------------------------------------------------------

        private void inline(String s, List<Map<String, Object>> marks) {
            StringBuilder text = new StringBuilder();
            int n = s.length();
            int i = 0;
            while (i < n) {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < n && isPunctuation(s.charAt(i + 1))) {
                    text.append(s.charAt(i + 1));
                    i += 2;
                } else if (c == HARD_BREAK) {
                    emit(text, marks);
                    builder.hardBreak();
                    i++;
                } else if (c == '\n') {
                    text.append(' ');
                    i++;
                } else if (c == '`') {
                    int run = run(s, i, '`');
                    int close = s.indexOf("`".repeat(run), i + run);
                    if (close < 0) {
                        text.append("`".repeat(run));
                    } else {
                        losses.lost("code");
                        emit(text, marks);
                        builder.text(s.substring(i + run, close).replace('\n', ' ').strip(), marks);
                        close += run;
                    }
                    i = close < 0 ? i + run : close;
                } else if (c == '!' && i + 1 < n && s.charAt(i + 1) == '[') {
                    i = image(s, i, text, marks);
                } else if (c == '[') {
                    i = link(s, i, text, marks);
                } else if (c == '<') {
                    i = autolink(s, i, text, marks);
                } else if (c == '*' || c == '_' || c == '~') {
                    i = emphasis(s, i, text, marks);
                } else {
                    text.append(c);
                    i++;
                }
            }
            emit(text, marks);
        }

        /** {@code [label](destination "title")}; anything short of that is literal text. */
        private int link(String s, int i, StringBuilder text, List<Map<String, Object>> marks) {
            int closeBracket = matching(s, i, '[', ']');
            if (closeBracket < 0 || closeBracket + 1 >= s.length() || s.charAt(closeBracket + 1) != '(') {
                text.append('[');
                return i + 1;
            }
            int closeParen = matching(s, closeBracket + 1, '(', ')');
            if (closeParen < 0) {
                text.append('[');
                return i + 1;
            }
            String destination = s.substring(closeBracket + 2, closeParen).strip();
            String url = destination.split("\\s+", 2)[0];
            if (url.startsWith("<") && url.endsWith(">")) {
                url = url.substring(1, url.length() - 1);
            }
            emit(text, marks);
            String label = s.substring(i + 1, closeBracket);
            boolean allowed = ProseMirrorSanitizer.isAllowedHref(url);
            if (!allowed) {
                losses.lost("link");
            }
            inline(label, allowed ? with(marks, link(url)) : marks);
            return closeParen + 1;
        }

        /** {@code ![alt](src)}: the schema has no images, so only its description is kept. */
        private int image(String s, int i, StringBuilder text, List<Map<String, Object>> marks) {
            int closeBracket = matching(s, i + 1, '[', ']');
            if (closeBracket < 0 || closeBracket + 1 >= s.length() || s.charAt(closeBracket + 1) != '('
                    || matching(s, closeBracket + 1, '(', ')') < 0) {
                text.append('!');
                return i + 1;
            }
            losses.lost("image");
            emit(text, marks);
            inline(s.substring(i + 2, closeBracket), marks);
            return matching(s, closeBracket + 1, '(', ')') + 1;
        }

        /** {@code <https://…>} and {@code <name@example.com>}. */
        private int autolink(String s, int i, StringBuilder text, List<Map<String, Object>> marks) {
            int close = s.indexOf('>', i);
            String inner = close < 0 ? "" : s.substring(i + 1, close);
            String href = null;
            if (!inner.isEmpty() && inner.chars().noneMatch(Character::isWhitespace)) {
                if (ProseMirrorSanitizer.isAllowedHref(inner)) {
                    href = inner;
                } else if (EMAIL.matcher(inner).matches()) {
                    href = "mailto:" + inner;
                }
            }
            if (href == null) {
                text.append('<');
                return i + 1;
            }
            emit(text, marks);
            builder.text(inner, with(marks, link(href)));
            return close + 1;
        }

        private int emphasis(String s, int i, StringBuilder text, List<Map<String, Object>> marks) {
            char c = s.charAt(i);
            int run = run(s, i, c);
            boolean opens = i + run < s.length() && !Character.isWhitespace(s.charAt(i + run))
                    && !(c == '_' && i > 0 && Character.isLetterOrDigit(s.charAt(i - 1)));
            if (opens) {
                if (c == '~') {
                    if (run == 2) {
                        int close = closer(s, i + 2, "~~");
                        if (close >= 0) {
                            emit(text, marks);
                            inline(s.substring(i + 2, close), with(marks, mark("strikethrough")));
                            return close + 2;
                        }
                    }
                } else {
                    if (run >= 3) {
                        String delimiter = String.valueOf(c).repeat(3);
                        int close = closer(s, i + 3, delimiter);
                        if (close >= 0) {
                            emit(text, marks);
                            inline(s.substring(i + 3, close), with(with(marks, mark("strong")), mark("em")));
                            return close + 3;
                        }
                    }
                    if (run >= 2) {
                        String delimiter = String.valueOf(c).repeat(2);
                        int close = closer(s, i + 2, delimiter);
                        if (close >= 0) {
                            text.append(String.valueOf(c).repeat(run - 2));
                            emit(text, marks);
                            inline(s.substring(i + 2, close), with(marks, mark("strong")));
                            return close + 2;
                        }
                    }
                    if (run == 1) {
                        int close = closer(s, i + 1, String.valueOf(c));
                        if (close >= 0) {
                            emit(text, marks);
                            inline(s.substring(i + 1, close), with(marks, mark("em")));
                            return close + 1;
                        }
                    }
                }
            }
            text.append(String.valueOf(c).repeat(run));
            return i + run;
        }

        /**
         * Where a delimiter run closes, or -1. A closer follows non-whitespace; a single delimiter
         * skips over doubled ones (so {@code *a **b** c*} closes at the end); a longer run closes
         * with its last characters, leaving the first to an inner span.
         */
        private int closer(String s, int from, String delimiter) {
            char c = delimiter.charAt(0);
            int length = delimiter.length();
            for (int k = from; k < s.length(); k++) {
                char ch = s.charAt(k);
                if (ch == '\\') {
                    k++;
                    continue;
                }
                if (ch != c) {
                    continue;
                }
                int run = run(s, k, c);
                boolean afterText = k > from && !Character.isWhitespace(s.charAt(k - 1));
                boolean intraword = c == '_' && k + run < s.length() && Character.isLetterOrDigit(s.charAt(k + run));
                if (afterText && !intraword && (run == length || (length > 1 && run > length))) {
                    return k + run - length;
                }
                k += run - 1;
            }
            return -1;
        }

        private void emit(StringBuilder text, List<Map<String, Object>> marks) {
            if (!text.isEmpty()) {
                builder.text(text.toString(), marks);
                text.setLength(0);
            }
        }

        private static int matching(String s, int open, char opening, char closing) {
            int depth = 0;
            for (int k = open; k < s.length(); k++) {
                char ch = s.charAt(k);
                if (ch == '\\') {
                    k++;
                } else if (ch == opening) {
                    depth++;
                } else if (ch == closing && --depth == 0) {
                    return k;
                }
            }
            return -1;
        }

        private static int run(String s, int i, char c) {
            int k = i;
            while (k < s.length() && s.charAt(k) == c) {
                k++;
            }
            return k - i;
        }

        private static int indentOf(String line) {
            int indent = 0;
            for (int k = 0; k < line.length(); k++) {
                char ch = line.charAt(k);
                if (ch == ' ') {
                    indent++;
                } else if (ch == '\t') {
                    indent += 4 - (indent % 4);
                } else {
                    break;
                }
            }
            return indent;
        }

        private static boolean isPunctuation(char c) {
            return c < 128 && !Character.isLetterOrDigit(c) && !Character.isWhitespace(c) && c > 32;
        }

        private static List<Map<String, Object>> with(List<Map<String, Object>> marks, Map<String, Object> mark) {
            List<Map<String, Object>> next = new ArrayList<>(marks);
            next.removeIf(existing -> existing.get("type").equals(mark.get("type")));
            next.add(mark);
            return next;
        }

        private static Map<String, Object> link(String href) {
            Map<String, Object> link = mark("link");
            link.put("attrs", new LinkedHashMap<>(Map.of("href", href)));
            return link;
        }

        private static Map<String, Object> mark(String type) {
            Map<String, Object> mark = new LinkedHashMap<>();
            mark.put("type", type);
            return mark;
        }
    }
}
