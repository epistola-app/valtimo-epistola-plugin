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

import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.CustomNode;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;
import org.jsoup.Jsoup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static app.epistola.valtimo.richtext.Nodes.node;

/**
 * Markdown, parsed with commonmark-java to the CommonMark specification plus GFM strikethrough.
 *
 * <p>What maps onto the schema is kept: paragraphs, hard line breaks, nested bullet and ordered
 * lists, {@code **strong**}, {@code *em*}, {@code ~~strikethrough~~} and links, autolinks
 * included. The rest keeps its text and is recorded as lost: a heading, a block quote or a code
 * block becomes a paragraph, inline code and raw HTML their plain text, an image its description.
 * Underline, sub- and superscript and colour have no markdown syntax and so never arise.
 */
public class MarkdownFormat implements RichTextFormat {

    public static final String ID = "markdown";

    private static final Parser PARSER = Parser.builder()
            .extensions(List.of(StrikethroughExtension.create()))
            .build();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Map<String, Object> toProseMirror(Object value, RichTextLosses losses) {
        String markdown = value == null ? "" : String.valueOf(value);
        Conversion conversion = new Conversion(losses);
        PARSER.parse(markdown).accept(conversion);
        return conversion.builder.build();
    }

    @Override
    public Object fromProseMirror(Map<String, Object> doc) {
        return ProseMirrorText.toMarkdown(doc);
    }

    /** One walk over a parsed document. Not reusable: it carries the state of the walk. */
    private static final class Conversion extends AbstractVisitor {

        private final RichTextLosses losses;
        private final ProseMirrorBuilder builder = new ProseMirrorBuilder();
        private List<Map<String, Object>> marks = List.of();

        Conversion(RichTextLosses losses) {
            this.losses = losses;
        }

        // --- Blocks ---------------------------------------------------------------------------

        @Override
        public void visit(Paragraph paragraph) {
            visitChildren(paragraph);
            builder.endBlock();
        }

        @Override
        public void visit(Heading heading) {
            losses.lost("heading");
            builder.endBlock();
            visitChildren(heading);
            builder.endBlock();
        }

        @Override
        public void visit(BlockQuote quote) {
            losses.lost("block quote");
            visitChildren(quote);
        }

        @Override
        public void visit(BulletList list) {
            builder.openList(false, null);
            visitChildren(list);
            builder.closeList();
        }

        @Override
        public void visit(OrderedList list) {
            builder.openList(true, list.getMarkerStartNumber());
            visitChildren(list);
            builder.closeList();
        }

        @Override
        public void visit(ListItem item) {
            builder.openItem();
            visitChildren(item);
            builder.closeItem();
        }

        @Override
        public void visit(FencedCodeBlock code) {
            codeBlock(code.getLiteral());
        }

        @Override
        public void visit(IndentedCodeBlock code) {
            codeBlock(code.getLiteral());
        }

        @Override
        public void visit(ThematicBreak rule) {
            losses.lost("horizontal rule");
            builder.endBlock();
        }

        /** Raw HTML in markdown: its text is kept, its markup is not interpreted. */
        @Override
        public void visit(HtmlBlock html) {
            losses.lost("html");
            builder.endBlock();
            builder.text(Jsoup.parseBodyFragment(html.getLiteral()).body().text(), marks);
            builder.endBlock();
        }

        private void codeBlock(String literal) {
            losses.lost("code block");
            builder.endBlock();
            String[] lines = literal.stripTrailing().split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) {
                    builder.hardBreak();
                }
                builder.text(lines[i], marks);
            }
            builder.endBlock();
        }

        // --- Inline ---------------------------------------------------------------------------

        @Override
        public void visit(Text text) {
            builder.text(text.getLiteral(), marks);
        }

        @Override
        public void visit(SoftLineBreak lineBreak) {
            builder.text(" ", marks);
        }

        @Override
        public void visit(HardLineBreak lineBreak) {
            builder.hardBreak();
        }

        @Override
        public void visit(Emphasis emphasis) {
            withMark(emphasis, node("em"));
        }

        @Override
        public void visit(StrongEmphasis strong) {
            withMark(strong, node("strong"));
        }

        @Override
        public void visit(CustomNode node) {
            if (node instanceof Strikethrough) {
                withMark(node, node("strikethrough"));
            } else {
                visitChildren(node);
            }
        }

        @Override
        public void visit(Link link) {
            String href = link.getDestination();
            if (!ProseMirrorSanitizer.isAllowedHref(href)) {
                losses.lost("link");
                visitChildren(link);
                return;
            }
            Map<String, Object> mark = node("link");
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("href", href);
            if (link.getTitle() != null && !link.getTitle().isEmpty()) {
                attrs.put("title", link.getTitle());
            }
            mark.put("attrs", attrs);
            withMark(link, mark);
        }

        /** The schema has no images, so only an image's description is kept. */
        @Override
        public void visit(Image image) {
            losses.lost("image");
            visitChildren(image);
        }

        @Override
        public void visit(Code code) {
            losses.lost("code");
            builder.text(code.getLiteral(), marks);
        }

        /** An inline tag such as {@code <b>}: dropped, the text around it is kept. */
        @Override
        public void visit(HtmlInline html) {
            losses.lost("html");
        }

        private void withMark(Node node, Map<String, Object> mark) {
            List<Map<String, Object>> outer = marks;
            List<Map<String, Object>> inner = new ArrayList<>(outer);
            inner.removeIf(existing -> existing.get("type").equals(mark.get("type")));
            inner.add(mark);
            marks = inner;
            visitChildren(node);
            marks = outer;
        }


    }
}
