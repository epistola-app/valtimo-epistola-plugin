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
 * Text with no markup: a blank line separates paragraphs, a single line break stays a line break.
 *
 * <p>The safe default (ADR 0008): it never invents formatting. A {@code *} or a {@code <} is a
 * character of the letter, not an instruction, which is what a case filled from a plain text area
 * means by it.
 */
public class PlainTextFormat implements RichTextFormat {

    public static final String ID = "plain";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Map<String, Object> toProseMirror(Object value) {
        List<Map<String, Object>> paragraphs = new ArrayList<>();
        String text = value == null ? "" : String.valueOf(value).replace("\r\n", "\n").replace('\r', '\n');
        for (String block : text.split("\n[ \t]*\n+")) {
            String trimmed = block.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            List<Map<String, Object>> inline = new ArrayList<>();
            String[] lines = trimmed.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) {
                    inline.add(node("hard_break"));
                }
                if (!lines[i].isEmpty()) {
                    Map<String, Object> textNode = node("text");
                    textNode.put("text", lines[i]);
                    inline.add(textNode);
                }
            }
            Map<String, Object> paragraph = node("paragraph");
            paragraph.put("content", inline);
            paragraphs.add(paragraph);
        }
        Map<String, Object> doc = node("doc");
        doc.put("content", paragraphs);
        return doc;
    }

    /** The document's text, keeping its lines and paragraphs and marking list items. */
    @Override
    public Object fromProseMirror(Map<String, Object> doc) {
        return ProseMirrorText.toPlainText(doc);
    }

    private static Map<String, Object> node(String type) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", type);
        return node;
    }
}
